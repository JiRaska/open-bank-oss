-- Idempotency ledger for source events. The payload stays on the owning service/topic; this table
-- records only the stable event identity and ordering version needed to make replay deterministic.
-- Rollback: stop the dispute-events consumer, then DROP TABLE context_projection_events. Retaining
-- the table is harmless and preserves replay evidence.
CREATE TABLE context_projection_events (
  bank_scope varchar(80) NOT NULL,
  event_key varchar(400) NOT NULL,
  source_system varchar(100) NOT NULL,
  aggregate_ref varchar(300) NOT NULL,
  source_version bigint NOT NULL,
  occurred_at timestamptz NOT NULL,
  processed_at timestamptz NOT NULL,
  PRIMARY KEY (bank_scope, event_key)
);
CREATE INDEX idx_context_projection_events_source
  ON context_projection_events(bank_scope, source_system, aggregate_ref, source_version DESC);
