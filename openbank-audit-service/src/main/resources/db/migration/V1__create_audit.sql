CREATE TABLE audit_entries (
    id              BIGSERIAL PRIMARY KEY,
    entry_id        UUID        NOT NULL UNIQUE,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    actor_id        VARCHAR(100),
    actor_type      VARCHAR(50),
    payload         TEXT         NOT NULL,
    source_service  VARCHAR(100) NOT NULL,
    correlation_id  VARCHAR(100),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_aggregate_id ON audit_entries(aggregate_id);
CREATE INDEX idx_audit_event_type ON audit_entries(event_type);
CREATE INDEX idx_audit_occurred_at ON audit_entries(occurred_at DESC);
CREATE INDEX idx_audit_actor_id ON audit_entries(actor_id) WHERE actor_id IS NOT NULL;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
