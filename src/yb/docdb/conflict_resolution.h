// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#ifndef YB_DOCDB_CONFLICT_RESOLUTION_H
#define YB_DOCDB_CONFLICT_RESOLUTION_H

#include <boost/function.hpp>

#include "yb/common/common_fwd.h"

#include "yb/docdb/docdb_fwd.h"
#include "yb/docdb/doc_operation.h"
#include "yb/docdb/intent.h"
#include "yb/docdb/shared_lock_manager.h"
#include "yb/docdb/wait_queue.h"

namespace rocksdb {

class DB;
class Iterator;

}

namespace yb {

class Counter;

namespace docdb {

// Note -- we use boost::function here instead of std::function as it's implementation is better
// suited for small callback instances.
using ResolutionCallback = boost::function<void(const Result<HybridTime>&)>;

// Resolves conflicts for write batch of transaction.
// Read all intents that could conflict with intents generated by provided write_batch.
// Forms set of conflicting transactions.
// Tries to abort transactions with lower priority.
// If it conflicts with transaction with higher priority or committed one then error is returned.
//
// write_batch - values that would be written as part of transaction.
// hybrid_time - current hybrid time.
// db - db that contains tablet data.
// status_manager - status manager that should be used during this conflict resolution.
// conflicts_metric - transaction_conflicts metric to update.
// lock_batch - a pointer to the lock_batch used by this operation, which will be temporarily
//              unlocked in the event that blocking conflicting transactions are found and
//              waited-on. Only used in conjunction with the wait_queue.
// wait_queue - a pointer to the tablet's wait queue. If null, we proceed with optimistic locking.
//              Else, we proceed with pessimistic locking and use the wait_queue to block and
//              unblock transactions with conflicts.
Status ResolveTransactionConflicts(const DocOperations& doc_ops,
                                   const KeyValueWriteBatchPB& write_batch,
                                   HybridTime resolution_ht,
                                   HybridTime read_time,
                                   const DocDB& doc_db,
                                   PartialRangeKeyIntents partial_range_key_intents,
                                   TransactionStatusManager* status_manager,
                                   Counter* conflicts_metric,
                                   LockBatch* lock_batch,
                                   WaitQueue* wait_queue,
                                   ResolutionCallback callback);

// Resolves conflicts for doc operations.
// Read all intents that could conflict with provided doc_ops.
// Forms set of conflicting transactions.
// Tries to abort conflicting transactions.
// If it conflicts with already committed transaction, then returns maximal commit time of such
// transaction. So we could update local clock and apply those operations later than conflicting
// transaction.
//
// doc_ops - doc operations that would be applied as part of operation.
// resolution_ht - current hybrid time. Used to request status of conflicting transactions.
// db - db that contains tablet data.
// status_manager - status manager that should be used during this conflict resolution.
Status ResolveOperationConflicts(const DocOperations& doc_ops,
                                 HybridTime resolution_ht,
                                 const DocDB& doc_db,
                                 PartialRangeKeyIntents partial_range_key_intents,
                                 TransactionStatusManager* status_manager,
                                 Counter* conflicts_metric,
                                 LockBatch* lock_batch,
                                 WaitQueue* wait_queue,
                                 ResolutionCallback callback);

struct ParsedIntent {
  // Intent DocPath.
  Slice doc_path;
  IntentTypeSet types;
  // Intent doc hybrid time.
  Slice doc_ht;
};

// Parses the intent pointed to by intent_iter to a ParsedIntent.
// Intent is encoded as Prefix + DocPath + IntentType + DocHybridTime.
// `transaction_id_source` could be larger that 16 bytes, it is not problem here, because it is
// used for error reporting.
Result<ParsedIntent> ParseIntentKey(Slice intent_key, Slice transaction_id_source);

std::string DebugIntentKeyToString(Slice intent_key);

} // namespace docdb
} // namespace yb

#endif // YB_DOCDB_CONFLICT_RESOLUTION_H
