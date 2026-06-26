-- EBA ICT Risk Guidelines + GDPR: Audit trail enhancements
-- V2: Session, user agent, data classification, retention

ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS session_id        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS user_agent        VARCHAR(500),
    ADD COLUMN IF NOT EXISTS ip_address        VARCHAR(45),
    ADD COLUMN IF NOT EXISTS data_sensitivity  VARCHAR(20) DEFAULT 'INTERNAL',
    ADD COLUMN IF NOT EXISTS retention_until   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS is_security_event BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS risk_score        SMALLINT;

-- EBA ICT: Security events need fast lookup
CREATE INDEX IF NOT EXISTS idx_audit_security ON audit_entries(is_security_event, occurred_at DESC)
    WHERE is_security_event = TRUE;
CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_entries(session_id) WHERE session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_retention ON audit_entries(retention_until);

-- EBA: Audit entries must be immutable - prevent updates
CREATE OR REPLACE RULE no_update_audit AS ON UPDATE TO audit_entries DO INSTEAD NOTHING;
CREATE OR REPLACE RULE no_delete_audit AS ON DELETE TO audit_entries DO INSTEAD NOTHING;

COMMENT ON COLUMN audit_entries.data_sensitivity IS 'GDPR: PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED';
COMMENT ON COLUMN audit_entries.retention_until IS 'EBA ICT + CNB: 10 year mandatory retention';
COMMENT ON COLUMN audit_entries.is_security_event IS 'EBA ICT: Security-relevant events for SIEM';
COMMENT ON TABLE audit_entries IS 'EBA ICT Risk: Immutable audit log - no UPDATE/DELETE allowed';

-- Populate retention_until for existing rows and set default via trigger
UPDATE audit_entries SET retention_until = occurred_at + INTERVAL '10 years' WHERE retention_until IS NULL;

CREATE OR REPLACE FUNCTION set_audit_retention()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.retention_until := NEW.occurred_at + INTERVAL '10 years';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_retention
    BEFORE INSERT ON audit_entries
    FOR EACH ROW EXECUTE FUNCTION set_audit_retention();
