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

#include "yb/docdb/doc_operation.h"
#include "yb/tablet/abstract_tablet.h"
#include "yb/util/trace.h"
#include "yb/yql/pgsql/ybpostgres/pg_send.h"

namespace yb {
namespace tablet {

CHECKED_STATUS AbstractTablet::HandleQLReadRequest(
    MonoTime deadline,
    const ReadHybridTime& read_time,
    const QLReadRequestPB& ql_read_request,
    const TransactionOperationContextOpt& txn_op_context,
    QLReadRequestResult* result) {

  // TODO(Robert): verify that all key column values are provided
  docdb::QLReadOperation doc_op(ql_read_request, txn_op_context);

  // Form a schema of columns that are referenced by this query.
  const Schema &schema = SchemaRef();
  Schema query_schema;
  const QLReferencedColumnsPB& column_pbs = ql_read_request.column_refs();
  vector<ColumnId> column_refs;
  for (int32_t id : column_pbs.static_ids()) {
    column_refs.emplace_back(id);
  }
  for (int32_t id : column_pbs.ids()) {
    column_refs.emplace_back(id);
  }
  RETURN_NOT_OK(schema.CreateProjectionByIdsIgnoreMissing(column_refs, &query_schema));

  QLRSRowDesc rsrow_desc(ql_read_request.rsrow_desc());
  QLResultSet resultset;
  TRACE("Start Execute");
  const Status s = doc_op.Execute(
      QLStorage(), deadline, read_time, schema, query_schema, &resultset, &result->restart_read_ht);
  TRACE("Done Execute");
  if (!s.ok()) {
    result->response.set_status(QLResponsePB::YQL_STATUS_RUNTIME_ERROR);
    result->response.set_error_message(s.message().cdata(), s.message().size());
    return Status::OK();
  }
  result->response.Swap(&doc_op.response());

  RETURN_NOT_OK(CreatePagingStateForRead(
      ql_read_request, resultset.rsrow_count(), &result->response));

  // TODO(neil) The clients' request should indicate what encoding method should be used. When
  // multi-shard is used to process more complicated queries, proxy-server might prefer a different
  // encoding. For now, we'll call CQLSerialize() without checking encoding method.
  result->response.set_status(QLResponsePB::YQL_STATUS_OK);
  TRACE("Start Serialize");
  RETURN_NOT_OK(resultset.CQLSerialize(ql_read_request.client(),
                                       rsrow_desc,
                                       &result->rows_data));
  TRACE("Done Serialize");
  return Status::OK();
}

CHECKED_STATUS AbstractTablet::HandlePgsqlReadRequest(
    MonoTime deadline,
    const ReadHybridTime& read_time,
    const PgsqlReadRequestPB& pgsql_read_request,
    const TransactionOperationContextOpt& txn_op_context,
    PgsqlReadRequestResult* result) {

  docdb::PgsqlReadOperation doc_op(pgsql_read_request, txn_op_context);

  // Form a schema of columns that are referenced by this query.
  const Schema &schema = SchemaRef();
  Schema query_schema;
  const PgsqlColumnRefsPB& column_pbs = pgsql_read_request.column_refs();
  vector<ColumnId> column_refs;
  for (int32_t id : column_pbs.ids()) {
    column_refs.emplace_back(id);
  }
  RETURN_NOT_OK(schema.CreateProjectionByIdsIgnoreMissing(column_refs, &query_schema));

  PgsqlRSRowDesc rsrow_desc(pgsql_read_request.rsrow_desc());
  PgsqlResultSet resultset;
  TRACE("Start Execute");
  const Status s = doc_op.Execute(QLStorage(), deadline, read_time, schema, query_schema,
                                  &resultset, &result->restart_read_ht);
  TRACE("Done Execute");
  if (!s.ok()) {
    result->response.set_status(PgsqlResponsePB::PGSQL_STATUS_RUNTIME_ERROR);
    result->response.set_error_message(s.message().cdata(), s.message().size());
    return Status::OK();
  }
  result->response.Swap(&doc_op.response());

  RETURN_NOT_OK(CreatePagingStateForRead(
      pgsql_read_request, resultset.rsrow_count(), &result->response));

  // TODO(neil) The clients' request should indicate what encoding method should be used. When
  // multi-shard is used to process more complicated queries, proxy-server might prefer a different
  // encoding. For now, we'll call PgsqlSerialize() without checking encoding method.
  result->response.set_status(PgsqlResponsePB::PGSQL_STATUS_OK);

  TRACE("Start Serialize");
  pgapi::PGSend sender;
  sender.WriteTupleDesc(rsrow_desc, &result->rows_data);
  sender.WriteTuples(resultset, rsrow_desc, &result->rows_data);
  TRACE("Done Serialize");

  return Status::OK();
}

}  // namespace tablet
}  // namespace yb
