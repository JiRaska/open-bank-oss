-- CNB + Czech Payment System: Domestic payment compliance
-- V2: Purpose code, actor, AML screening, CNB reporting

ALTER TABLE domestic_payments
    ADD COLUMN IF NOT EXISTS purpose_code          VARCHAR(4),
    ADD COLUMN IF NOT EXISTS payment_category      VARCHAR(20) DEFAULT 'STANDARD',
    ADD COLUMN IF NOT EXISTS actor_id              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS channel               VARCHAR(20) DEFAULT 'API',
    ADD COLUMN IF NOT EXISTS ip_address            VARCHAR(45),
    ADD COLUMN IF NOT EXISTS sca_reference         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS aml_screened          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS aml_screened_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cnb_reporting_code    VARCHAR(10),
    ADD COLUMN IF NOT EXISTS value_date            DATE,
    ADD COLUMN IF NOT EXISTS specific_symbol       VARCHAR(10),
    ADD COLUMN IF NOT EXISTS constant_symbol       VARCHAR(4);

-- CNB: Constant symbol constraint (Czech payment standard)
ALTER TABLE domestic_payments
    ADD CONSTRAINT chk_domestic_constant_symbol
    CHECK (constant_symbol IS NULL OR constant_symbol ~ '^[0-9]{1,4}$');

CREATE INDEX IF NOT EXISTS idx_domestic_actor ON domestic_payments(actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_domestic_aml ON domestic_payments(aml_screened) WHERE aml_screened = FALSE;
CREATE INDEX IF NOT EXISTS idx_domestic_cnb ON domestic_payments(cnb_reporting_code) WHERE cnb_reporting_code IS NOT NULL;

COMMENT ON COLUMN domestic_payments.constant_symbol IS 'CNB: Czech payment constant symbol (0-9999)';
COMMENT ON COLUMN domestic_payments.specific_symbol IS 'CNB: Czech payment specific symbol';
COMMENT ON COLUMN domestic_payments.cnb_reporting_code IS 'CNB: Cross-border reporting code';
COMMENT ON COLUMN domestic_payments.sca_reference IS 'PSD2 RTS Art. 97: SCA authentication reference';
