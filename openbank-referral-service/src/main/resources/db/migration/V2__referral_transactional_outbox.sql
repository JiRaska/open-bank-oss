-- Referral state and lifecycle events must commit atomically. The dispatcher retries PENDING/FAILED
-- rows and atomically claims work across rolling/canary pods.
CREATE TABLE referral_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ
);
CREATE INDEX idx_referral_outbox_status_created_at ON referral_outbox(status, created_at ASC);
CREATE INDEX idx_referral_outbox_aggregate_id ON referral_outbox(aggregate_id);
CREATE SEQUENCE IF NOT EXISTS referral_outbox_seq INCREMENT BY 50;

-- Rollback: stop the dispatcher, then DROP TABLE referral_outbox and DROP SEQUENCE referral_outbox_seq.
