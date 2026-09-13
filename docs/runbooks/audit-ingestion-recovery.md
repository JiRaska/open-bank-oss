<!-- SPDX-License-Identifier: Apache-2.0 -->
# Audit ingestion failure

`AuditIngestionFailed` reports a recent failure to parse or persist an audit record. It also
catches a counter whose first observed value is already positive. The alert's eventual resolution
means the observation window passed; it does not certify that failed records were replayed.
A healthy hash chain proves integrity of stored rows, not completeness of ingestion.

## Preserve and reconcile the event

1. Identify the failing consumer and the original producer event ID. Retain the original Kafka
   topic, partition and offset, including the dead-letter headers, alongside the original payload.
   Keep event contents in the access-controlled operational evidence store.
2. Inspect the consumer error and the configured failure strategy. A completed NACK delegates to
   that strategy; a failed NACK is not evidence that the dead-letter send succeeded. Verify the
   record's durable location before advancing a recovery workflow.
3. Reconcile the immutable event ID against the append-only store. A lost database or broker reply
   can follow a committed write. Do not create a new event ID merely to bypass a duplicate.
4. Repair the producing or storage fault, then replay using the original identity and payload.
   Confirm the expected audit row, its attribution and the chain verification result. Preserve
   failed-record evidence until the recovery has been reviewed.

For events without a producer ID, the consumer derives identity from the original broker address.
Replay tooling must preserve that address when calling the ingestion seam; a payload-only replay
or a new broker offset cannot recreate it. The broker integration test covers replay with a
producer ID. Metadata-only operational replay requires separate deployment validation.

The String DLQ serializer preserves the original event text. Records produced by the older default
serializer may instead contain a JSON-encoded string wrapping that text. Such historical transport
wrapping must be decoded once before an approved replay, while retaining the original evidence;
do not treat a JSON string as a substitute event object.

## Rollout and rollback

Deploy the consumer and its failure alert together. Validate NACK delivery and replay in the target
environment with its actual topic permissions and retention. The local broker test does not prove
production ACLs or high availability. A rollback to acknowledgement-on-failure reintroduces data
loss and is not a safe operational recovery.

All replicas writing the audit hash chain must use the database append lock. Drain older writers
before relying on concurrent append safety. A binary rollback requires a single writer and
reconciliation of in-flight writes. Existing rows and hashes are never rewritten by this change.

Each append allocates its numeric row ID from the existing database sequence while holding the
append lock. Per-process cached ID blocks are not used. The sequence and existing rows stay
unchanged; gaps between numeric IDs are expected and are not missing-event evidence.
