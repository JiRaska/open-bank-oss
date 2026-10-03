-- Rollback: stop sensitive Context reads and the relay, then DROP TABLE context_audit_commitment_outbox;
-- Keep context_read_audit and already exported central records. Dropping pending rows loses
-- unexported evidence, so production rollback retains this table until reconciliation completes.
CREATE TABLE context_audit_commitment_outbox (
  audit_id uuid PRIMARY KEY REFERENCES context_read_audit(audit_id),
  bank_scope varchar(80) NOT NULL,
  commitment char(64) NOT NULL,
  occurred_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  claimed_at timestamptz,
  sent_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT context_audit_outbox_status CHECK (status IN ('PENDING', 'DISPATCHING', 'FAILED', 'SENT'))
);
CREATE INDEX idx_context_audit_outbox_dispatch ON context_audit_commitment_outbox(bank_scope, status, updated_at);
ALTER TABLE context_audit_commitment_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_audit_commitment_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY context_audit_outbox_bank_scope ON context_audit_commitment_outbox
  USING (bank_scope = current_setting('openbank.bank_scope', true))
  WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

-- A committed commitment is evidence even after relay; status may change, identity and digest may not.
CREATE FUNCTION reject_context_audit_outbox_evidence_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.audit_id IS DISTINCT FROM OLD.audit_id OR NEW.bank_scope IS DISTINCT FROM OLD.bank_scope
     OR NEW.commitment IS DISTINCT FROM OLD.commitment OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
    RAISE EXCEPTION 'context audit commitment evidence is immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER context_audit_outbox_evidence_immutable
  BEFORE UPDATE ON context_audit_commitment_outbox
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_outbox_evidence_change();
CREATE TRIGGER context_audit_outbox_no_delete
  BEFORE DELETE ON context_audit_commitment_outbox
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
