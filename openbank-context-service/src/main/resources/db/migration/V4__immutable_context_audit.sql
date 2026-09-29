-- Application-level audit evidence is append-only. A database administrator can still perform
-- controlled retention/restore operations by disabling the trigger under an audited runbook.
-- Rollback: DROP TRIGGER context_read_audit_immutable ON context_read_audit; DROP TRIGGER
-- context_assignment_change_audit_immutable ON context_assignment_change_audit; then drop function.
CREATE FUNCTION reject_context_audit_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'context audit records are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER context_read_audit_immutable
  BEFORE UPDATE OR DELETE ON context_read_audit
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

CREATE TRIGGER context_assignment_change_audit_immutable
  BEFORE UPDATE OR DELETE ON context_assignment_change_audit
  FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
