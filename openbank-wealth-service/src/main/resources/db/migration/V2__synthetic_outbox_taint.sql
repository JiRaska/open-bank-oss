-- ADR-0252: a synthetic customer's traffic must stay identifiable across the outbox hand-off.
-- The persisted row IS the boundary — OutboxKafkaHeaders reconstructs the transport header from
-- it — so a column that is missing here silently launders a synthetic event into a real-looking
-- one. Safe default FALSE: every pre-existing row is real traffic.
-- Rollback: ALTER TABLE wealth_outbox DROP COLUMN synthetic;

ALTER TABLE wealth_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
