-- Retain the append-only audit on rollback; disabling this policy would weaken isolation.
-- The runtime sets openbank.bank_scope transaction-locally before every write or authorized read.
ALTER TABLE context_read_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_read_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY context_read_audit_bank_scope ON context_read_audit
  USING (bank_scope = current_setting('openbank.bank_scope', true))
  WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
