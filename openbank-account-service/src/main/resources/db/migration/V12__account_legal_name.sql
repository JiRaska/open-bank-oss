-- OpenBank Account Service — V12: add legal_name column for GDPR Art. 17 erasure (ADR-0118)
-- The column is nullable because:
--   (a) existing rows pre-date this migration and have no stored name;
--   (b) the erasure path sets the value to NULL to remove PII.

ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS legal_name VARCHAR(255);
