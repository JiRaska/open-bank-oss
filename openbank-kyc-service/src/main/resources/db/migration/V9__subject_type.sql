-- ADR-0284 D5: a KYC case knows whether its subject is a natural person or a legal entity, so the
-- check set (KYB checks for a business) and the cockpit can tell the two apart. Existing rows are
-- individuals — every party created before this migration was one.
-- Rollback: ALTER TABLE kyc_cases DROP COLUMN subject_type;

ALTER TABLE kyc_cases ADD COLUMN IF NOT EXISTS subject_type VARCHAR(16) NOT NULL DEFAULT 'INDIVIDUAL';
ALTER TABLE kyc_cases ADD CONSTRAINT chk_kyc_subject_type CHECK (subject_type IN ('INDIVIDUAL', 'BUSINESS'));
CREATE INDEX IF NOT EXISTS idx_kyc_subject_type ON kyc_cases (subject_type);
