-- ADR-0118 §2/§5 + issue #268: session/access-log retention enforcement.
--
-- Session logs are behavioural PII with NO statutory retention requirement (ADR-0118: "90 days,
-- proportionality only") — unlike audit_entries, which is hard-locked to a 10-year EBA/CNB
-- retention via the no_update_audit / no_delete_audit RULEs (V1). This table is intentionally
-- separate and NOT subject to those rules: it is mutable/prunable by design so the 90-day
-- retention scheduler (SessionLogRetentionScheduler) can actually delete expired rows.
--
-- Rollback: DROP TABLE session_logs; DROP SEQUENCE session_logs_seq;

CREATE TABLE IF NOT EXISTS session_logs (
    id              BIGSERIAL PRIMARY KEY,
    log_id          UUID         NOT NULL UNIQUE,
    party_id        UUID,
    session_id      VARCHAR(100) NOT NULL,
    actor_id        VARCHAR(100),
    event_type      VARCHAR(50)  NOT NULL,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_session_logs_occurred_at ON session_logs(occurred_at);
CREATE INDEX IF NOT EXISTS idx_session_logs_party_id ON session_logs(party_id) WHERE party_id IS NOT NULL;

-- PanacheEntity allocates ids from "<table>_seq" INCREMENT BY 50 (see V4 for the same defect
-- class on audit_entries/audit_outbox); generation:none means the sequence must exist explicitly.
CREATE SEQUENCE IF NOT EXISTS session_logs_seq INCREMENT BY 50;

COMMENT ON TABLE session_logs IS
    'ADR-0118 behavioural PII (session/access logs) - 90-day retention, deliberately mutable and NOT covered by the audit_entries immutability RULEs.';

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
