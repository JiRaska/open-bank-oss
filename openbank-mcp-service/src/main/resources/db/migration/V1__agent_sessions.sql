-- ADR-0224 D2: agent sessions — a staff OBO session issued after step-up, bounding the
-- operator's roles to a ceiling with a purpose and an expiry. Revocation must be instant,
-- so the resolver validates the session LIVE on every OBO call.
CREATE TABLE agent_session (
    id              UUID PRIMARY KEY,
    subject         VARCHAR(255) NOT NULL,
    role_ceiling    TEXT NOT NULL,
    client_id       VARCHAR(255) NOT NULL,
    purpose         VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX idx_agent_session_subject ON agent_session(subject) WHERE revoked_at IS NULL;
