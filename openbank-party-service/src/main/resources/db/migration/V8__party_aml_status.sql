-- AML screening outcome — second key of the KYC+AML activation gate (ADR-0073).
-- Existing parties default to NOT_SCREENED (not yet AML-cleared).
ALTER TABLE parties ADD COLUMN IF NOT EXISTS aml_status VARCHAR(20) NOT NULL DEFAULT 'NOT_SCREENED';
