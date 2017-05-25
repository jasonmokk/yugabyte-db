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

#ifndef YB_RPC_SERVICE_POOL_H
#define YB_RPC_SERVICE_POOL_H

#include <string>
#include <vector>

#include "yb/gutil/macros.h"
#include "yb/gutil/gscoped_ptr.h"
#include "yb/gutil/ref_counted.h"
#include "yb/rpc/rpc_service.h"
#include "yb/util/blocking_queue.h"
#include "yb/util/mutex.h"
#include "yb/util/thread.h"
#include "yb/util/status.h"

namespace yb {

class Counter;
class Histogram;
class MetricEntity;
class Socket;

namespace rpc {

class Messenger;
class ServiceIf;
class ThreadPool;
class ServicePoolImpl;

// A pool of threads that handle new incoming RPC calls.
// Also includes a queue that calls get pushed onto for handling by the pool.
class ServicePool : public RpcService {
 public:
  ServicePool(size_t max_tasks,
              ThreadPool* thread_pool,
              std::unique_ptr<ServiceIf> service,
              const scoped_refptr<MetricEntity>& metric_entity);
  virtual ~ServicePool();

  // Shut down the queue and the thread pool.
  virtual void Shutdown();

  virtual void QueueInboundCall(InboundCallPtr call) override;
  const Counter* RpcsTimedOutInQueueMetricForTests() const;
  const Counter* RpcsQueueOverflowMetric() const;
  std::string service_name() const;

 private:
  std::unique_ptr<ServicePoolImpl> impl_;
};

} // namespace rpc
} // namespace yb

#endif // YB_RPC_SERVICE_POOL_H
