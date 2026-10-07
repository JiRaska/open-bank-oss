-- Additive durable audit. Drain old writers before relying on complete coverage.
-- Rollback: retain this table and pending records; rollback of code stops dispatch, not evidence.
CREATE SEQUENCE settlement_outbox_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE settlement_outbox (
    id BIGINT PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL REFERENCES settlements(id),
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL CHECK (created_at >= '2020-01-01T00:00:00Z'),
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_settlement_outbox_status_created_at ON settlement_outbox(status, created_at);
CREATE INDEX idx_settlement_outbox_aggregate_id ON settlement_outbox(aggregate_id);
CREATE INDEX idx_settlement_outbox_unsent_created_at ON settlement_outbox(created_at) WHERE status <> 'SENT';
