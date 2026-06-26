-- PSD2 RTS + SEPA Credit Transfer Rulebook: Payment compliance
-- V2: Purpose code, charge bearer, regulatory fields

ALTER TABLE sepa_payments
    ADD COLUMN IF NOT EXISTS purpose_code          VARCHAR(4),
    ADD COLUMN IF NOT EXISTS charge_bearer         VARCHAR(4) NOT NULL DEFAULT 'SLEV',
    ADD COLUMN IF NOT EXISTS instructed_agent_bic  VARCHAR(11),
    ADD COLUMN IF NOT EXISTS category_purpose      VARCHAR(4),
    ADD COLUMN IF NOT EXISTS regulatory_reporting  TEXT,
    ADD COLUMN IF NOT EXISTS actor_id              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS channel               VARCHAR(20) DEFAULT 'API',
    ADD COLUMN IF NOT EXISTS ip_address            VARCHAR(45),
    ADD COLUMN IF NOT EXISTS sca_reference         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS consent_id            UUID,
    ADD COLUMN IF NOT EXISTS aml_screened          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS aml_screened_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS value_date            DATE;

-- PSD2: Charge bearer must be SLEV for SEPA CT
ALTER TABLE sepa_payments
    ADD CONSTRAINT chk_sepa_charge_bearer CHECK (charge_bearer IN ('DEBT','CRED','SHAR','SLEV'));

CREATE INDEX IF NOT EXISTS idx_sepa_actor ON sepa_payments(actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sepa_consent ON sepa_payments(consent_id) WHERE consent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sepa_aml ON sepa_payments(aml_screened) WHERE aml_screened = FALSE;
CREATE INDEX IF NOT EXISTS idx_sepa_value_date ON sepa_payments(value_date) WHERE value_date IS NOT NULL;

COMMENT ON COLUMN sepa_payments.purpose_code IS 'ISO 20022: SALA/RENT/TAXS/PENS etc.';
COMMENT ON COLUMN sepa_payments.charge_bearer IS 'SEPA CT Rulebook: Always SLEV for SEPA';
COMMENT ON COLUMN sepa_payments.sca_reference IS 'PSD2 RTS Art. 97: SCA authentication reference';
COMMENT ON COLUMN sepa_payments.consent_id IS 'PSD2: TPP consent reference';
