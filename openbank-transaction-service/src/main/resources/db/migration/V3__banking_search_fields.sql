-- V3: Czech banking system search fields + counterparty + IBAN/BBAN
-- Required for CNB compliance, BIAN alignment, and operational search

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS source_iban          VARCHAR(34),
    ADD COLUMN IF NOT EXISTS source_bban          VARCHAR(16),
    ADD COLUMN IF NOT EXISTS target_iban          VARCHAR(34),
    ADD COLUMN IF NOT EXISTS target_bban          VARCHAR(16),
    ADD COLUMN IF NOT EXISTS counterparty_name    VARCHAR(140),
    ADD COLUMN IF NOT EXISTS counterparty_bank_bic VARCHAR(11),
    ADD COLUMN IF NOT EXISTS remittance_info      VARCHAR(140),
    ADD COLUMN IF NOT EXISTS end_to_end_id        VARCHAR(35),
    ADD COLUMN IF NOT EXISTS transaction_code     VARCHAR(10),
    ADD COLUMN IF NOT EXISTS bank_transaction_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS proprietary_code     VARCHAR(35),
    ADD COLUMN IF NOT EXISTS fee_amount           NUMERIC(20,6),
    ADD COLUMN IF NOT EXISTS fee_currency         CHAR(3),
    ADD COLUMN IF NOT EXISTS exchange_rate_type   VARCHAR(20),
    ADD COLUMN IF NOT EXISTS instructed_amount    NUMERIC(20,6),
    ADD COLUMN IF NOT EXISTS instructed_currency  CHAR(3),
    ADD COLUMN IF NOT EXISTS batch_id             VARCHAR(35),
    ADD COLUMN IF NOT EXISTS mandate_id           VARCHAR(35),
    ADD COLUMN IF NOT EXISTS creditor_scheme_id   VARCHAR(35),
    ADD COLUMN IF NOT EXISTS category_purpose     VARCHAR(4),
    ADD COLUMN IF NOT EXISTS local_instrument     VARCHAR(35),
    ADD COLUMN IF NOT EXISTS clearing_system_ref  VARCHAR(35),
    ADD COLUMN IF NOT EXISTS settlement_date      DATE,
    ADD COLUMN IF NOT EXISTS is_reversal          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_fee_transaction   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS technical_account_id UUID;

-- Search indexes for BIAN-aligned transaction search
CREATE INDEX IF NOT EXISTS idx_tx_source_iban    ON transactions(source_iban) WHERE source_iban IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tx_target_iban    ON transactions(target_iban) WHERE target_iban IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tx_source_bban    ON transactions(source_bban) WHERE source_bban IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tx_target_bban    ON transactions(target_bban) WHERE target_bban IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tx_counterparty   ON transactions(counterparty_name) WHERE counterparty_name IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tx_end_to_end     ON transactions(end_to_end_id) WHERE end_to_end_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tx_amount         ON transactions(amount, booking_date DESC);
CREATE INDEX IF NOT EXISTS idx_tx_fee            ON transactions(is_fee_transaction, booking_date DESC);
CREATE INDEX IF NOT EXISTS idx_tx_technical_acct ON transactions(technical_account_id) WHERE technical_account_id IS NOT NULL;

COMMENT ON COLUMN transactions.source_iban IS 'IBAN of debit account (ISO 13616)';
COMMENT ON COLUMN transactions.target_iban IS 'IBAN of credit account (ISO 13616)';
COMMENT ON COLUMN transactions.source_bban IS 'Czech BBAN format: XXXXXX-XXXXXXXXXX/XXXX';
COMMENT ON COLUMN transactions.target_bban IS 'Czech BBAN format: XXXXXX-XXXXXXXXXX/XXXX';
COMMENT ON COLUMN transactions.counterparty_name IS 'ISO 20022 creditor/debtor name';
COMMENT ON COLUMN transactions.end_to_end_id IS 'PSD2/SEPA end-to-end identification';
COMMENT ON COLUMN transactions.bank_transaction_code IS 'ISO 20022 BankTransactionCode (e.g. PMNT-RCDT-ESCT)';
COMMENT ON COLUMN transactions.is_fee_transaction IS 'True for fee/charge transactions (FEE type)';
COMMENT ON COLUMN transactions.technical_account_id IS 'Link to technical/GL account for fee/suspense transactions';
