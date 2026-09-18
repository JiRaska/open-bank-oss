-- Rollback: disable the receipt relay and retain pending rows until Context has reconciled them.
-- Do not drop a populated receipt outbox during ordinary rollback: it proves which fleet entries
-- were committed but not yet acknowledged downstream.
CREATE TABLE context_commitment_receipts (
  event_id uuid PRIMARY KEY REFERENCES audit_entries(entry_id),
  commitment char(64) NOT NULL,
  record_hash char(64) NOT NULL,
  occurred_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  claimed_at timestamptz,
  sent_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT context_receipt_status CHECK (status IN ('PENDING', 'DISPATCHING', 'FAILED', 'SENT')),
  CONSTRAINT context_receipt_commitment_sha256 CHECK (commitment ~ '^[0-9a-f]{64}$'),
  CONSTRAINT context_receipt_record_hash_sha256 CHECK (record_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_context_receipts_dispatch ON context_commitment_receipts(status, updated_at);

CREATE FUNCTION reject_context_receipt_evidence_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.commitment IS DISTINCT FROM OLD.commitment
     OR NEW.record_hash IS DISTINCT FROM OLD.record_hash OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
    RAISE EXCEPTION 'context receipt evidence is immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER context_receipt_evidence_immutable
  BEFORE UPDATE ON context_commitment_receipts
  FOR EACH ROW EXECUTE FUNCTION reject_context_receipt_evidence_change();
