-- A durable reference-only tombstone, independent of delivery order. It must be retained
-- while recorded references exist: deleting it would re-expose restricted observations.
-- Rollback before traffic: DROP TABLE context_kyb_observation_restrictions;
-- After traffic, retain the table and fail closed until readers understand restrictions.
CREATE TABLE context_kyb_observation_restrictions (
    bank_scope VARCHAR(64) NOT NULL,
    observation_id UUID NOT NULL,
    event_id UUID NOT NULL,
    case_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    source_sha256 CHAR(64) NOT NULL,
    restricted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bank_scope, observation_id)
);

CREATE TRIGGER context_kyb_observation_restrictions_append_only
    BEFORE UPDATE OR DELETE ON context_kyb_observation_restrictions
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_kyb_observation_restrictions ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_kyb_observation_restrictions FORCE ROW LEVEL SECURITY;
CREATE POLICY context_kyb_observation_restriction_bank_scope ON context_kyb_observation_restrictions
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

GRANT ALL ON context_kyb_observation_restrictions TO openbank;
