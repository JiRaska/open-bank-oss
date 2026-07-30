-- ADR-0226: cross-channel audit correlation dimensions. session_id already exists on this table
-- since V2__compliance_fields.sql (VARCHAR(100), idx_audit_session) — this migration adds only the
-- two remaining dimensions. Tamper-evidence is unaffected: the chain-hashed payload column
-- carries the producer's event JSON verbatim, these are query indexes derived from it.
ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS channel VARCHAR(16),
    ADD COLUMN IF NOT EXISTS act_chain TEXT;
