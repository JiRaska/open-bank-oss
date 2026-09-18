-- Rollback: stop sensitive Context reads, then DROP the disclosure-only constraint and
-- columns below. Retain the append-only rows and their exported commitments in any
-- environment that has handled investigative reads; never delete evidentiary history.
ALTER TABLE context_read_audit
  ADD COLUMN access_audit_id uuid REFERENCES context_read_audit(audit_id),
  ADD COLUMN query_hash char(64),
  ADD COLUMN projection_generation char(64),
  ADD COLUMN evidence_refs text,
  ADD COLUMN evidence_count integer,
  ADD COLUMN response_truncated boolean,
  ADD COLUMN response_status integer;

ALTER TABLE context_read_audit ADD CONSTRAINT context_disclosure_outcome_complete CHECK (
  decision NOT IN ('DISCLOSED', 'NOT_DISCLOSED') OR (
    access_audit_id IS NOT NULL AND query_hash IS NOT NULL AND
    evidence_refs IS NOT NULL AND evidence_count BETWEEN 0 AND 500 AND
    response_truncated IS NOT NULL AND response_status BETWEEN 200 AND 599 AND
    octet_length(evidence_refs) <= 65536 AND
    (decision = 'NOT_DISCLOSED' OR projection_generation IS NOT NULL)
  )
);
CREATE FUNCTION verify_context_disclosure_access() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  access_row context_read_audit%ROWTYPE;
BEGIN
  IF NEW.decision IN ('DISCLOSED', 'NOT_DISCLOSED') THEN
    SELECT * INTO access_row FROM context_read_audit WHERE audit_id = NEW.access_audit_id;
    IF NOT FOUND OR access_row.decision <> 'ALLOWED'
       OR access_row.bank_scope IS DISTINCT FROM NEW.bank_scope
       OR access_row.principal_id IS DISTINCT FROM NEW.principal_id
       OR access_row.case_id IS DISTINCT FROM NEW.case_id
       OR access_row.purpose IS DISTINCT FROM NEW.purpose
       OR access_row.action IS DISTINCT FROM NEW.action
       OR access_row.root_ref IS DISTINCT FROM NEW.root_ref THEN
      RAISE EXCEPTION 'disclosure outcome does not match an allowed read';
    END IF;
    IF jsonb_typeof(NEW.evidence_refs::jsonb) <> 'array' THEN
      RAISE EXCEPTION 'disclosure evidence references must be an array';
    END IF;
  ELSIF NEW.access_audit_id IS NOT NULL THEN
    RAISE EXCEPTION 'only disclosure outcomes may cite an allowed read';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER context_disclosure_access_matches
  BEFORE INSERT ON context_read_audit FOR EACH ROW EXECUTE FUNCTION verify_context_disclosure_access();
CREATE INDEX idx_context_disclosure_access_audit ON context_read_audit(bank_scope, access_audit_id)
  WHERE access_audit_id IS NOT NULL;
