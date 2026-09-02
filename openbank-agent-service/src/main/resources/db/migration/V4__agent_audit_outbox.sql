-- Durable handoff for AI provenance (ADR-0031 D5).  Additive table; rollback disables the
-- dispatcher but retains unsent evidence for later recovery.
-- Rollback: DROP TABLE agent_audit_outbox; -- rows are unpublished evidence, not a
-- reconstructible cache — export them first if the dispatcher's audit trail must be kept.
CREATE TABLE agent_audit_outbox (
    event_id UUID PRIMARY KEY,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX idx_agent_audit_outbox_ready
    ON agent_audit_outbox (created_at)
    WHERE published_at IS NULL;
