-- GDPR Art. 5(1)(e) / ADR-0118 §5: track when party PII was erased so the scheduled
-- retention job can delete the case after the AML 5-year hold period expires.
ALTER TABLE kyc_cases ADD COLUMN IF NOT EXISTS erased_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_kyc_cases_erased_at ON kyc_cases (erased_at) WHERE erased_at IS NOT NULL;
