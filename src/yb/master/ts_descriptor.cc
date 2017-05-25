// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "yb/master/ts_descriptor.h"

#include <math.h>

#include <mutex>
#include <vector>

#include "yb/common/wire_protocol.h"
#include "yb/consensus/consensus.proxy.h"
#include "yb/gutil/strings/substitute.h"
#include "yb/master/master.pb.h"
#include "yb/tserver/tserver_admin.proxy.h"
#include "yb/tserver/tserver_service.proxy.h"
#include "yb/util/net/net_util.h"

using std::shared_ptr;

namespace yb {
namespace master {

Status TSDescriptor::RegisterNew(const NodeInstancePB& instance,
                                 const TSRegistrationPB& registration,
                                 gscoped_ptr<TSDescriptor>* desc) {
  gscoped_ptr<TSDescriptor> ret(new TSDescriptor(instance.permanent_uuid()));
  RETURN_NOT_OK(ret->Register(instance, registration));
  desc->swap(ret);
  return Status::OK();
}

TSDescriptor::TSDescriptor(std::string perm_id)
    : permanent_uuid_(std::move(perm_id)),
      latest_seqno_(-1),
      last_heartbeat_(MonoTime::Now(MonoTime::FINE)),
      has_tablet_report_(false),
      recent_replica_creations_(0),
      last_replica_creations_decay_(MonoTime::Now(MonoTime::FINE)),
      num_live_replicas_(0) {
}

TSDescriptor::~TSDescriptor() {
}

Status TSDescriptor::Register(const NodeInstancePB& instance,
                              const TSRegistrationPB& registration) {
  std::lock_guard<simple_spinlock> l(lock_);
  CHECK_EQ(instance.permanent_uuid(), permanent_uuid_);

  if (instance.instance_seqno() < latest_seqno_) {
    return STATUS(AlreadyPresent,
      strings::Substitute("Cannot register with sequence number $0:"
                          " Already have a registration from sequence number $1",
                          instance.instance_seqno(),
                          latest_seqno_));
  } else if (instance.instance_seqno() == latest_seqno_) {
    // It's possible that the TS registered, but our response back to it
    // got lost, so it's trying to register again with the same sequence
    // number. That's fine.
    LOG(INFO) << "Processing retry of TS registration from " << instance.ShortDebugString();
  }

  latest_seqno_ = instance.instance_seqno();
  // After re-registering, make the TS re-report its tablets.
  has_tablet_report_ = false;

  registration_.reset(new TSRegistrationPB(registration));
  placement_id_ = generate_placement_id(registration.common().cloud_info());

  ts_admin_proxy_.reset();
  ts_service_proxy_.reset();
  consensus_proxy_.reset();

  return Status::OK();
}

std::string TSDescriptor::generate_placement_id(const CloudInfoPB& ci) {
  return strings::Substitute(
      "$0:$1:$2", ci.placement_cloud(), ci.placement_region(), ci.placement_zone());
}

std::string TSDescriptor::placement_id() const {
  std::lock_guard<simple_spinlock> l(lock_);
  return placement_id_;
}

void TSDescriptor::UpdateHeartbeatTime() {
  std::lock_guard<simple_spinlock> l(lock_);
  last_heartbeat_ = MonoTime::Now(MonoTime::FINE);
}

MonoDelta TSDescriptor::TimeSinceHeartbeat() const {
  MonoTime now(MonoTime::Now(MonoTime::FINE));
  std::lock_guard<simple_spinlock> l(lock_);
  return now.GetDeltaSince(last_heartbeat_);
}

int64_t TSDescriptor::latest_seqno() const {
  std::lock_guard<simple_spinlock> l(lock_);
  return latest_seqno_;
}

bool TSDescriptor::has_tablet_report() const {
  std::lock_guard<simple_spinlock> l(lock_);
  return has_tablet_report_;
}

void TSDescriptor::set_has_tablet_report(bool has_report) {
  std::lock_guard<simple_spinlock> l(lock_);
  has_tablet_report_ = has_report;
}

void TSDescriptor::DecayRecentReplicaCreationsUnlocked() {
  // In most cases, we won't have any recent replica creations, so
  // we don't need to bother calling the clock, etc.
  if (recent_replica_creations_ == 0) return;

  const double kHalflifeSecs = 60;
  MonoTime now = MonoTime::Now(MonoTime::FINE);
  double secs_since_last_decay = now.GetDeltaSince(last_replica_creations_decay_).ToSeconds();
  recent_replica_creations_ *= pow(0.5, secs_since_last_decay / kHalflifeSecs);

  // If sufficiently small, reset down to 0 to take advantage of the fast path above.
  if (recent_replica_creations_ < 1e-12) {
    recent_replica_creations_ = 0;
  }
  last_replica_creations_decay_ = now;
}

void TSDescriptor::IncrementRecentReplicaCreations() {
  std::lock_guard<simple_spinlock> l(lock_);
  DecayRecentReplicaCreationsUnlocked();
  recent_replica_creations_ += 1;
}

double TSDescriptor::RecentReplicaCreations() {
  std::lock_guard<simple_spinlock> l(lock_);
  DecayRecentReplicaCreationsUnlocked();
  return recent_replica_creations_;
}

void TSDescriptor::GetRegistration(TSRegistrationPB* reg) const {
  std::lock_guard<simple_spinlock> l(lock_);
  CHECK(registration_) << "No registration";
  CHECK_NOTNULL(reg)->CopyFrom(*registration_);
}

void TSDescriptor::GetTSInformationPB(TSInformationPB* ts_info) const {
  GetRegistration(ts_info->mutable_registration());
  GetNodeInstancePB(ts_info->mutable_tserver_instance());
}

bool TSDescriptor::MatchesCloudInfo(const CloudInfoPB& cloud_info) const {
  std::lock_guard<simple_spinlock> l(lock_);
  const auto& ci = registration_->common().cloud_info();

  return cloud_info.placement_cloud() == ci.placement_cloud() &&
         cloud_info.placement_region() == ci.placement_region() &&
         cloud_info.placement_zone() == ci.placement_zone();
}

bool TSDescriptor::IsRunningOn(const HostPortPB& hp) const {
  TSRegistrationPB reg;
  GetRegistration(&reg);
  for (const auto& rpc_hp : reg.common().rpc_addresses()) {
    if (hp.host() == rpc_hp.host() && hp.port() == rpc_hp.port()) {
      return true;
    }
  }
  return false;
}

void TSDescriptor::GetNodeInstancePB(NodeInstancePB* instance_pb) const {
  std::lock_guard<simple_spinlock> l(lock_);
  instance_pb->set_permanent_uuid(permanent_uuid_);
  instance_pb->set_instance_seqno(latest_seqno_);
}

Status TSDescriptor::ResolveEndpoint(Endpoint* addr) const {
  vector<HostPort> hostports;
  {
    std::lock_guard<simple_spinlock> l(lock_);
    for (const HostPortPB& addr : registration_->common().rpc_addresses()) {
      hostports.emplace_back(addr.host(), addr.port());
    }
  }

  // Resolve DNS outside the lock.
  HostPort last_hostport;
  std::vector<Endpoint> addrs;
  for (const HostPort& hostport : hostports) {
    RETURN_NOT_OK(hostport.ResolveAddresses(&addrs));
    if (!addrs.empty()) {
      last_hostport = hostport;
      break;
    }
  }

  if (addrs.size() == 0) {
    return STATUS(NetworkError, "Unable to find the TS address: ", registration_->DebugString());
  }

  if (addrs.size() > 1) {
    LOG(WARNING) << "TS address " << last_hostport.ToString()
                  << " resolves to " << addrs.size() << " different addresses. Using "
                  << addrs[0];
  }
  *addr = addrs[0];
  return Status::OK();
}

Status TSDescriptor::GetTSAdminProxy(const shared_ptr<rpc::Messenger>& messenger,
                                     shared_ptr<tserver::TabletServerAdminServiceProxy>* proxy) {
  {
    std::lock_guard<simple_spinlock> l(lock_);
    if (ts_admin_proxy_) {
      *proxy = ts_admin_proxy_;
      return Status::OK();
    }
  }

  Endpoint addr;
  RETURN_NOT_OK(ResolveEndpoint(&addr));

  std::lock_guard<simple_spinlock> l(lock_);
  if (!ts_admin_proxy_) {
    ts_admin_proxy_.reset(new tserver::TabletServerAdminServiceProxy(messenger, addr));
  }
  *proxy = ts_admin_proxy_;
  return Status::OK();
}

Status TSDescriptor::GetConsensusProxy(const shared_ptr<rpc::Messenger>& messenger,
                                       shared_ptr<consensus::ConsensusServiceProxy>* proxy) {
  {
    std::lock_guard<simple_spinlock> l(lock_);
    if (consensus_proxy_) {
      *proxy = consensus_proxy_;
      return Status::OK();
    }
  }

  Endpoint addr;
  RETURN_NOT_OK(ResolveEndpoint(&addr));

  std::lock_guard<simple_spinlock> l(lock_);
  if (!consensus_proxy_) {
    consensus_proxy_.reset(new consensus::ConsensusServiceProxy(messenger, addr));
  }
  *proxy = consensus_proxy_;
  return Status::OK();
}

Status TSDescriptor::GetTSServiceProxy(const shared_ptr<rpc::Messenger>& messenger,
                                       shared_ptr<tserver::TabletServerServiceProxy>* proxy) {
  {
    std::lock_guard<simple_spinlock> l(lock_);
    if (ts_service_proxy_) {
      *proxy = ts_service_proxy_;
      return Status::OK();
    }
  }

  Endpoint addr;
  RETURN_NOT_OK(ResolveEndpoint(&addr));

  std::lock_guard<simple_spinlock> l(lock_);
  if (!ts_service_proxy_) {
    ts_service_proxy_.reset(new tserver::TabletServerServiceProxy(messenger, addr));
  }
  *proxy = ts_service_proxy_;
  return Status::OK();
}

bool TSDescriptor::HasTabletDeletePending() const {
  std::lock_guard<simple_spinlock> l(lock_);
  return !tablets_pending_delete_.empty();
}

bool TSDescriptor::IsTabletDeletePending(const std::string& tablet_id) const {
  std::lock_guard<simple_spinlock> l(lock_);
  return tablets_pending_delete_.count(tablet_id);
}

void TSDescriptor::AddPendingTabletDelete(const std::string& tablet_id) {
  std::lock_guard<simple_spinlock> l(lock_);
  tablets_pending_delete_.insert(tablet_id);
}

void TSDescriptor::ClearPendingTabletDelete(const std::string& tablet_id) {
  std::lock_guard<simple_spinlock> l(lock_);
  tablets_pending_delete_.erase(tablet_id);
}

} // namespace master
} // namespace yb
