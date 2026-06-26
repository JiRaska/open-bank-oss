CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    notification_id UUID NOT NULL UNIQUE,
    party_id    UUID NOT NULL,
    channel     VARCHAR(10) NOT NULL,
    template    VARCHAR(50) NOT NULL,
    recipient   VARCHAR(255) NOT NULL,
    subject     VARCHAR(500),
    body        TEXT NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    metadata    JSONB NOT NULL DEFAULT '{}',
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_party_id ON notifications(party_id);
CREATE INDEX idx_notifications_status ON notifications(status);
GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
