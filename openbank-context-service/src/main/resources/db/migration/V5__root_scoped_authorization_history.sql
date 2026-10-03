-- Rollback: stop the history consumer/read endpoint and retain append-only evidence.
-- The nullable root columns keep existing complaint/incident assignments compatible.
ALTER TABLE context_case_assignments ADD COLUMN root_ref varchar(300);
ALTER TABLE context_assignment_proposals ADD COLUMN root_ref varchar(300);
ALTER TABLE context_assignment_change_audit ADD COLUMN root_ref varchar(300);
CREATE INDEX idx_context_assignment_root ON context_case_assignments
  (bank_scope, principal_id, case_id, purpose, root_ref, valid_to);

ALTER TABLE context_read_audit ADD COLUMN effective_at timestamptz;
ALTER TABLE context_read_audit ADD COLUMN known_at timestamptz;

CREATE TABLE context_authority_history (
  bank_scope varchar(80) NOT NULL,
  delegation_id uuid NOT NULL,
  revision bigint NOT NULL CHECK (revision >= 0),
  event_type varchar(80) NOT NULL,
  observation_id uuid NOT NULL UNIQUE,
  evidence text NOT NULL CHECK (jsonb_typeof(evidence::jsonb) = 'object'),
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  evidence_ref varchar(300) NOT NULL,
  content_hash varchar(64) NOT NULL,
  PRIMARY KEY (bank_scope, delegation_id, revision)
);
CREATE INDEX idx_context_authority_history_snapshot ON context_authority_history
  (bank_scope, delegation_id, occurred_at DESC, recorded_at DESC);
CREATE TRIGGER context_authority_history_append_only
  BEFORE UPDATE OR DELETE ON context_authority_history
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_authority_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_authority_history FORCE ROW LEVEL SECURITY;
CREATE POLICY context_authority_bank_scope ON context_authority_history
  USING (bank_scope = current_setting('openbank.bank_scope', true))
  WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
