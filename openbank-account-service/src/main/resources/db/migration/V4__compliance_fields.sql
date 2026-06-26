-- EBA/CNB/PSD2 Compliance: Account enhancements
-- V2: Add IBAN, closure reason, dormancy tracking, data retention

ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS iban                VARCHAR(34),
    ADD COLUMN IF NOT EXISTS bic                 VARCHAR(11),
    ADD COLUMN IF NOT EXISTS closed_reason       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS dormancy_date       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS data_retention_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS onboarding_channel  VARCHAR(20) DEFAULT 'API',
    ADD COLUMN IF NOT EXISTS risk_category       VARCHAR(20) DEFAULT 'STANDARD';

-- PSD2 Art. 35: IBAN must be unique per account
CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_iban ON accounts(iban) WHERE iban IS NOT NULL;

-- CNB: Index for dormancy monitoring
CREATE INDEX IF NOT EXISTS idx_accounts_dormancy ON accounts(dormancy_date) WHERE dormancy_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_accounts_retention ON accounts(data_retention_until) WHERE data_retention_until IS NOT NULL;

COMMENT ON COLUMN accounts.iban IS 'IBAN per PSD2 Art. 35 - required for payment initiation';
COMMENT ON COLUMN accounts.data_retention_until IS 'GDPR Art. 17 + CNB: 10 years after closure';
COMMENT ON COLUMN accounts.risk_category IS 'AML risk category: LOW/STANDARD/HIGH/PEP';
