-- Rollback: disable new disclosure export and sensitive reads; retain all local outcomes
-- and pending rows until they are reconciled with the fleet audit chain.
ALTER TABLE context_disclosure_audit
  ADD CONSTRAINT context_disclosure_bank_id UNIQUE (bank_scope, disclosure_id);

CREATE TABLE context_disclosure_commitment_outbox (
  disclosure_id uuid PRIMARY KEY,
  bank_scope varchar(80) NOT NULL,
  commitment char(64) NOT NULL CHECK (commitment ~ '^[0-9a-f]{64}$'),
  occurred_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  claimed_at timestamptz,
  sent_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT context_disclosure_outbox_status
    CHECK (status IN ('PENDING', 'DISPATCHING', 'FAILED', 'SENT')),
  CONSTRAINT context_disclosure_outbox_same_bank FOREIGN KEY (bank_scope, disclosure_id)
    REFERENCES context_disclosure_audit(bank_scope, disclosure_id)
);
CREATE INDEX idx_context_disclosure_outbox_dispatch
  ON context_disclosure_commitment_outbox(bank_scope, status, updated_at);

ALTER TABLE context_disclosure_commitment_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_disclosure_commitment_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY context_disclosure_outbox_bank_scope ON context_disclosure_commitment_outbox
  USING (bank_scope = current_setting('openbank.bank_scope', true))
  WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

CREATE FUNCTION reject_context_disclosure_outbox_evidence_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.disclosure_id IS DISTINCT FROM OLD.disclosure_id
     OR NEW.bank_scope IS DISTINCT FROM OLD.bank_scope
     OR NEW.commitment IS DISTINCT FROM OLD.commitment
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
    RAISE EXCEPTION 'context disclosure commitment evidence is immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER context_disclosure_outbox_evidence_immutable
  BEFORE UPDATE ON context_disclosure_commitment_outbox
  FOR EACH ROW EXECUTE FUNCTION reject_context_disclosure_outbox_evidence_change();
CREATE TRIGGER context_disclosure_outbox_no_delete
  BEFORE DELETE ON context_disclosure_commitment_outbox
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
