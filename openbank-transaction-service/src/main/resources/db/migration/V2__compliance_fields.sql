-- EBA ICT Risk + PSD2 + CNB: Transaction audit trail enhancements
-- V2: Add actor, channel, correlation, IP for full audit trail

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS actor_id        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS actor_type      VARCHAR(20) DEFAULT 'SYSTEM',
    ADD COLUMN IF NOT EXISTS channel         VARCHAR(20) DEFAULT 'API',
    ADD COLUMN IF NOT EXISTS ip_address      VARCHAR(45),
    ADD COLUMN IF NOT EXISTS correlation_id  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS purpose_code    VARCHAR(4),
    ADD COLUMN IF NOT EXISTS regulatory_reporting_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS aml_screened    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS aml_screened_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reversal_of     UUID;

-- EBA: Actor and channel indexes for audit queries
CREATE INDEX IF NOT EXISTS idx_transactions_actor ON transactions(actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transactions_channel ON transactions(channel);
CREATE INDEX IF NOT EXISTS idx_transactions_correlation ON transactions(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transactions_aml ON transactions(aml_screened, booking_date) WHERE aml_screened = FALSE;

COMMENT ON COLUMN transactions.actor_id IS 'EBA ICT: Who initiated - user/service ID';
COMMENT ON COLUMN transactions.channel IS 'PSD2: API/BRANCH/ATM/MOBILE/INTERNET';
COMMENT ON COLUMN transactions.purpose_code IS 'ISO 20022 purpose code (SALA, RENT, TAXS...)';
COMMENT ON COLUMN transactions.aml_screened IS 'AML 5AMLD: Must be screened before completion';
COMMENT ON COLUMN transactions.regulatory_reporting_code IS 'CNB: Regulatory reporting code for cross-border';
