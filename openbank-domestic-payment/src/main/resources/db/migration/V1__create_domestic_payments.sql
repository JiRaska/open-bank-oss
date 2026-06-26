CREATE TABLE domestic_payments (
    id BIGSERIAL PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    debtor_account_id UUID NOT NULL,
    debtor_account_number VARCHAR(34) NOT NULL,
    debtor_bank_code VARCHAR(4) NOT NULL,
    debtor_name VARCHAR(255) NOT NULL,
    creditor_account_number VARCHAR(34) NOT NULL,
    creditor_bank_code VARCHAR(4) NOT NULL,
    creditor_name VARCHAR(255) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    variable_symbol VARCHAR(10),
    specific_symbol VARCHAR(10),
    constant_symbol VARCHAR(4),
    message_for_payee VARCHAR(140),
    priority VARCHAR(16) NOT NULL,
    statement_label VARCHAR(140),
    end_to_end_id VARCHAR(64) NOT NULL,
    reject_reason VARCHAR(64),
    reject_detail TEXT,
    submitted_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_domestic_payments_status ON domestic_payments(status);
CREATE INDEX idx_domestic_payments_debtor_account_id ON domestic_payments(debtor_account_id);
CREATE INDEX idx_domestic_payments_created_at ON domestic_payments(created_at DESC);
