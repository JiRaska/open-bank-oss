-- Rollback: stop the AML evidence consumer and read endpoint; retain the append-only
-- observations after adoption so historical investigations remain reproducible.
CREATE TABLE context_aml_case_evidence (
  bank_scope varchar(80) NOT NULL,
  event_id uuid NOT NULL,
  case_id uuid NOT NULL,
  party_id uuid NOT NULL,
  account_id uuid,
  transaction_id uuid,
  event_type varchar(80) NOT NULL,
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  evidence text NOT NULL CHECK (jsonb_typeof(evidence::jsonb) = 'object'),
  content_hash varchar(64) NOT NULL,
  PRIMARY KEY (bank_scope, event_id)
);
CREATE INDEX idx_context_aml_case_evidence_snapshot ON context_aml_case_evidence
  (bank_scope, case_id, occurred_at DESC, recorded_at DESC);
-- Explicit case links are indexed for bounded, authorized graph exploration. A shared
-- account or transaction is a lead; this index grants no cross-case read permission.
CREATE INDEX idx_context_aml_case_party ON context_aml_case_evidence (bank_scope, party_id, case_id);
CREATE INDEX idx_context_aml_case_account ON context_aml_case_evidence (bank_scope, account_id, case_id)
  WHERE account_id IS NOT NULL;
CREATE INDEX idx_context_aml_case_transaction ON context_aml_case_evidence (bank_scope, transaction_id, case_id)
  WHERE transaction_id IS NOT NULL;
CREATE TRIGGER context_aml_case_evidence_append_only
  BEFORE UPDATE OR DELETE ON context_aml_case_evidence
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_aml_case_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_aml_case_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY context_aml_case_bank_scope ON context_aml_case_evidence
  USING (bank_scope = current_setting('openbank.bank_scope', true))
  WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
