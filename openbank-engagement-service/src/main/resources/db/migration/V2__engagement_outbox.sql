CREATE TABLE engagement_outbox (
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

CREATE INDEX idx_engagement_outbox_status_created_at ON engagement_outbox(status, created_at ASC);
CREATE INDEX idx_engagement_outbox_aggregate_id ON engagement_outbox(aggregate_id);
