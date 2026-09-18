-- Source-owned ICT incident lifecycle window for evidence-based case correlation.
-- A time overlap alone does not prove that an incident affected a business case.
-- Rollback before traffic: DROP TABLE context_incident_windows;
-- After traffic, stop the incident consumer, retain the table for audit, then replay from source after rollback.
CREATE TABLE context_incident_windows (
    bank_scope VARCHAR(64) NOT NULL,
    incident_id UUID NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    contained_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED')),
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bank_scope, incident_id)
);

CREATE INDEX idx_context_incident_windows_time
    ON context_incident_windows (bank_scope, detected_at DESC, incident_id);

ALTER TABLE context_incident_windows ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_incident_windows FORCE ROW LEVEL SECURITY;
CREATE POLICY context_incident_windows_bank_scope ON context_incident_windows
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

GRANT ALL ON context_incident_windows TO openbank;
