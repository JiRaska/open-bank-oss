-- Transactional outbox (ADR-0013 shared primitives, ADR-0143 phase 2c): the intent-to-post
-- ledger journal for one non-waived, non-zero AssessedFee. aggregate_id = the assessed_fee.id
-- so a redrive can be traced back to exactly one fee row. Mirrors interest_outbox
-- (openbank-interest-service) column-for-column — see PanacheOutboxEntity (openbank-libs-runtime)
-- for the shared shape this table backs.

CREATE TABLE billing_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_billing_outbox_status_created_at ON billing_outbox(status, created_at ASC);
CREATE INDEX idx_billing_outbox_aggregate_id ON billing_outbox(aggregate_id);
