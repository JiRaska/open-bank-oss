-- EBA AML Guidelines + FATF: KYC/CDD compliance enhancements
-- V2: Due diligence level, source of funds, business purpose

ALTER TABLE kyc_cases
    ADD COLUMN IF NOT EXISTS due_diligence_level   VARCHAR(10) NOT NULL DEFAULT 'CDD',
    ADD COLUMN IF NOT EXISTS source_of_funds       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_of_wealth      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS business_purpose      VARCHAR(200),
    ADD COLUMN IF NOT EXISTS expected_turnover     NUMERIC(20,2),
    ADD COLUMN IF NOT EXISTS expected_turnover_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS pep_declaration       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS beneficial_owner_id   UUID,
    ADD COLUMN IF NOT EXISTS screening_provider    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS screening_ref         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS next_review_date      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS escalated_to          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS escalated_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS escalation_reason     TEXT;

-- EBA: Due diligence level constraint
ALTER TABLE kyc_cases
    ADD CONSTRAINT chk_kyc_due_diligence CHECK (due_diligence_level IN ('SDD','CDD','EDD'));

CREATE INDEX IF NOT EXISTS idx_kyc_due_diligence ON kyc_cases(due_diligence_level);
CREATE INDEX IF NOT EXISTS idx_kyc_review_date ON kyc_cases(next_review_date) WHERE next_review_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_kyc_pep ON kyc_cases(pep_declaration) WHERE pep_declaration = TRUE;

COMMENT ON COLUMN kyc_cases.due_diligence_level IS 'EBA AML: SDD=Simplified, CDD=Standard, EDD=Enhanced';
COMMENT ON COLUMN kyc_cases.source_of_funds IS 'FATF R.10: Source of funds declaration';
COMMENT ON COLUMN kyc_cases.pep_declaration IS 'AML 5AMLD: Customer PEP self-declaration';
COMMENT ON COLUMN kyc_cases.next_review_date IS 'EBA: Periodic review - HIGH=1yr, MEDIUM=2yr, LOW=3yr';
