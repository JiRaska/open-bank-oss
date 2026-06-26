-- ADR-0049 D3: drop the redundant event_key column from transaction_outbox.
-- The partition key (aggregate_id) is now computed by OutboxKafkaHeaders.partitionKey()
-- from the aggregate_id column, which is semantically identical to what event_key stored.
-- Rollback note: re-add the column as nullable (existing rows will have NULL) and
-- backfill with aggregate_id::text if needed for a downgrade.
ALTER TABLE transaction_outbox DROP COLUMN IF EXISTS event_key;
