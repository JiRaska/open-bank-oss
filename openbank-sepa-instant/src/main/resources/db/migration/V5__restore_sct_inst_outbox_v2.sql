-- Durable events are inserted in the same transaction as their payment transition.
-- V4 removed the unused legacy table; never edit or replay that historical migration.
-- Rollback: stop all writers first. Do not drop this table merely because dispatch is disabled:
-- the new app writes PENDING rows regardless. Keep the schema until every row has an approved
-- disposition and a read-only check confirms no recoverable PENDING/FAILED/DISPATCHING/DEAD row.
-- If the old app is restored, retain a compatible relay for these rows until drained; the old
-- app has no outbox dispatcher. Only then may an approved rollback drop this table and sequence.
CREATE SEQUENCE sct_inst_outbox_seq INCREMENT BY 50;

CREATE TABLE sct_inst_outbox (
    id BIGINT PRIMARY KEY DEFAULT nextval('sct_inst_outbox_seq'),
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ
);

CREATE INDEX ix_sct_inst_outbox_claim ON sct_inst_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
CREATE INDEX ix_sct_inst_outbox_inflight_aggregate ON sct_inst_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
