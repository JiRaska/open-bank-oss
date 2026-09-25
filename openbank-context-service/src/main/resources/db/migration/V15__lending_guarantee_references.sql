-- Approved guarantee pointers only. A pointer never establishes a graph relationship.
-- Rollback before traffic: DROP TABLE context_lending_guarantee_references;
-- After traffic, stop the consumer and retain rows until replay/rebuild is verified.
CREATE TABLE context_lending_guarantee_references (
    bank_scope VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    guarantee_id UUID NOT NULL,
    loan_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bank_scope, event_id),
    CONSTRAINT uq_context_lending_guarantee_revision UNIQUE (bank_scope, guarantee_id, revision)
);

CREATE INDEX idx_context_lending_guarantee_loan
    ON context_lending_guarantee_references (bank_scope, loan_id);

CREATE TRIGGER context_lending_guarantee_references_append_only
    BEFORE UPDATE OR DELETE ON context_lending_guarantee_references
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_lending_guarantee_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_lending_guarantee_references FORCE ROW LEVEL SECURITY;
CREATE POLICY context_lending_guarantee_bank_scope ON context_lending_guarantee_references
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

GRANT ALL ON context_lending_guarantee_references TO openbank;
