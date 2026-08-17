-- PR #1364 (2026-07-17) removed the sct_inst_outbox transactional-outbox pipeline
-- (SctInstOutboxPort/SctInstOutboxDispatcher/KafkaSctInstOutboxEventPublisher) after confirming
-- it was never wired to any real call site: KafkaSctInstEventPublisher (a direct, synchronous
-- emitter) was always the pipeline actually in use (issue #1034). That PR could not drop the
-- table itself — needs its own migration, this one — and left it behind at 0 rows (issue #5127).
-- Rollback: CREATE TABLE sct_inst_outbox (id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL UNIQUE,
--   aggregate_id UUID NOT NULL, event_type VARCHAR(128) NOT NULL, payload TEXT NOT NULL,
--   status VARCHAR(16) NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, sent_at TIMESTAMPTZ,
--   last_error TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL
--   DEFAULT NOW()); CREATE INDEX idx_sct_inst_outbox_status_created_at ON
--   sct_inst_outbox(status, created_at ASC); CREATE INDEX idx_sct_inst_outbox_aggregate_id ON
--   sct_inst_outbox(aggregate_id); CREATE SEQUENCE IF NOT EXISTS sct_inst_outbox_seq INCREMENT BY 50;

DROP TABLE IF EXISTS sct_inst_outbox;
DROP SEQUENCE IF EXISTS sct_inst_outbox_seq;
