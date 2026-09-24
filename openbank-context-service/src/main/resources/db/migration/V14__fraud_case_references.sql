-- Fraud case lifecycle pointers only; account and counterparty identifiers stay in Fraud.
-- Rollback before traffic: DROP TABLE context_fraud_case_references;
-- After traffic, stop the consumer first and retain these rows until replay/rebuild is verified.
CREATE TABLE context_fraud_case_references (
    bank_scope VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    case_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('fraud.case_opened', 'fraud.case_closed')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bank_scope, event_id),
    CONSTRAINT uq_context_fraud_case_revision UNIQUE (bank_scope, case_id, revision)
);

CREATE INDEX idx_context_fraud_case_history
    ON context_fraud_case_references (bank_scope, case_id, revision DESC);

CREATE TRIGGER context_fraud_case_references_append_only
    BEFORE UPDATE OR DELETE ON context_fraud_case_references
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_fraud_case_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_fraud_case_references FORCE ROW LEVEL SECURITY;
CREATE POLICY context_fraud_case_bank_scope ON context_fraud_case_references
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

GRANT ALL ON context_fraud_case_references TO openbank;
