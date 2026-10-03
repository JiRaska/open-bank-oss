-- Reference-only KYB observations. Owner attributes and mapped findings remain in KYB.
-- Rollback before traffic: DROP TABLE context_kyb_observation_references;
-- After traffic, roll back the consumer first; retain rows until replay/rebuild has been checked.
CREATE TABLE context_kyb_observation_references (
    bank_scope VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    case_id UUID NOT NULL,
    observation_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    source_sha256 CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bank_scope, event_id),
    CONSTRAINT uq_context_kyb_observation_revision UNIQUE (bank_scope, observation_id, revision)
);

CREATE INDEX idx_context_kyb_observation_case_revision
    ON context_kyb_observation_references (bank_scope, case_id, revision DESC);

CREATE TRIGGER context_kyb_observation_references_append_only
    BEFORE UPDATE OR DELETE ON context_kyb_observation_references
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_kyb_observation_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_kyb_observation_references FORCE ROW LEVEL SECURITY;
CREATE POLICY context_kyb_observation_bank_scope ON context_kyb_observation_references
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

GRANT ALL ON context_kyb_observation_references TO openbank;
