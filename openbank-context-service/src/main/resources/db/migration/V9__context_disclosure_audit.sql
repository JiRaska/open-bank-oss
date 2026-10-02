-- A successful access decision is not proof of a completed disclosure.
-- Rollback: stop sensitive Context reads; retain this append-only evidence until its
-- retention and export obligations are satisfied. Do not drop it during a live rollback.
ALTER TABLE context_read_audit ADD CONSTRAINT context_read_audit_bank_id UNIQUE (bank_scope, audit_id);

CREATE TABLE context_disclosure_audit (
  disclosure_id uuid PRIMARY KEY,
  decision_audit_id uuid NOT NULL,
  bank_scope varchar(80) NOT NULL,
  query_hash char(64) NOT NULL,
  projection_generation bigint,
  evidence_refs_json text NOT NULL,
  evidence_count integer NOT NULL CHECK (evidence_count >= 0),
  truncated boolean NOT NULL,
  occurred_at timestamptz NOT NULL,
  CONSTRAINT context_disclosure_query_hash CHECK (query_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT context_disclosure_refs_size CHECK (octet_length(evidence_refs_json) <= 131072),
  CONSTRAINT context_disclosure_decision_same_bank FOREIGN KEY (bank_scope, decision_audit_id)
    REFERENCES context_read_audit(bank_scope, audit_id)
);

CREATE UNIQUE INDEX idx_context_disclosure_decision ON context_disclosure_audit(bank_scope, decision_audit_id);
ALTER TABLE context_disclosure_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_disclosure_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY context_disclosure_bank_scope ON context_disclosure_audit
  USING (bank_scope = current_setting('openbank.bank_scope', true))
  WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
CREATE TRIGGER context_disclosure_audit_immutable
  BEFORE UPDATE OR DELETE ON context_disclosure_audit
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
