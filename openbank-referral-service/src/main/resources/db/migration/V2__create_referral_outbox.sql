-- Transactional outbox for referral domain events (ADR-0049/ADR-0050), closing #7190: the
-- program/invite/reward tables already existed but nothing durably recorded a Qualified /
-- RewardRequested / RewardOutcome event alongside them.
-- Rollback: DROP TABLE referral_outbox;

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
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_referral_outbox_status_created_at ON referral_outbox(status, created_at ASC);
CREATE INDEX idx_referral_outbox_aggregate_id ON referral_outbox(aggregate_id);
