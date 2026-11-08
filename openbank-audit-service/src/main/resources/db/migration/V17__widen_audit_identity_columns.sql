-- Widen the producer-supplied identity columns of audit_entries from VARCHAR(100) to VARCHAR(512).
--
-- AuditConsumer copies these values from the event verbatim, and it must not truncate them: the
-- stored values are chain-hashed into record_hash, so a shortened value would be a different fact.
-- A value over the column limit therefore fails the INSERT (SQLState 22001), the record is nacked
-- to openbank.dlq.audit.audit-events-in, and the audit trail has no row for that event. No producer
-- contract bounds these fields at 100 characters.
--
-- 512 stays a bound on purpose: a larger value still dead-letters, where it can be inspected, and
-- all six columns remain btree-indexable. aggregate_type, actor_type, channel, on_behalf_of and
-- delegation_id have narrower closed vocabularies or id formats and are not changed here.
--
-- Widening a VARCHAR is metadata-only in Postgres: no table rewrite and no index rebuild, and the
-- stored values, and so every record_hash, are untouched.
--
-- Rollback: forward-only once a row exceeds 100 characters, because narrowing then fails and the
-- table is append-only. Before that, ALTER each column back to TYPE VARCHAR(100).
ALTER TABLE audit_entries
    ALTER COLUMN event_type TYPE VARCHAR(512),
    ALTER COLUMN aggregate_id TYPE VARCHAR(512),
    ALTER COLUMN actor_id TYPE VARCHAR(512),
    ALTER COLUMN source_service TYPE VARCHAR(512),
    ALTER COLUMN correlation_id TYPE VARCHAR(512),
    ALTER COLUMN session_id TYPE VARCHAR(512);
