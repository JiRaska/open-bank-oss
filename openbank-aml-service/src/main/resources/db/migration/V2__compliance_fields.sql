-- FATF + EU 5AMLD/6AMLD + EBA AML Guidelines: AML compliance
-- V2: Matched lists, false positive tracking, SAR reference

ALTER TABLE aml_cases
    ADD COLUMN IF NOT EXISTS matched_list          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS matched_entity_name   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS match_score           NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS false_positive        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS false_positive_reason TEXT,
    ADD COLUMN IF NOT EXISTS false_positive_by     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS false_positive_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sar_filed             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS sar_reference         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS sar_filed_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reviewed_by           VARCHAR(100),
    ADD COLUMN IF NOT EXISTS reviewed_at           TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS escalated_to_mlro     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS escalated_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS transaction_id        UUID,
    ADD COLUMN IF NOT EXISTS amount                NUMERIC(20,6),
    ADD COLUMN IF NOT EXISTS currency              CHAR(3),
    ADD COLUMN IF NOT EXISTS notes                 TEXT;

-- 6AMLD: SAR filing tracking
CREATE INDEX IF NOT EXISTS idx_aml_sar ON aml_cases(sar_filed, created_at DESC) WHERE sar_filed = TRUE;
CREATE INDEX IF NOT EXISTS idx_aml_false_positive ON aml_cases(false_positive) WHERE false_positive = FALSE;
CREATE INDEX IF NOT EXISTS idx_aml_mlro ON aml_cases(escalated_to_mlro) WHERE escalated_to_mlro = TRUE;
CREATE INDEX IF NOT EXISTS idx_aml_transaction ON aml_cases(transaction_id) WHERE transaction_id IS NOT NULL;

COMMENT ON COLUMN aml_cases.matched_list IS '5AMLD: EU Consolidated Sanctions/PEP/Adverse Media list';
COMMENT ON COLUMN aml_cases.sar_filed IS '6AMLD Art. 6: Suspicious Activity Report to FIU';
COMMENT ON COLUMN aml_cases.escalated_to_mlro IS 'AML: Money Laundering Reporting Officer escalation';
COMMENT ON COLUMN aml_cases.match_score IS 'Fuzzy match score 0-100 for name screening';
