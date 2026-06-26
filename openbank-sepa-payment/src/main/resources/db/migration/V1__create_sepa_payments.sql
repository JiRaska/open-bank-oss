CREATE TABLE sepa_payments (
    id BIGSERIAL PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    payment_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    debtor_account_id UUID NOT NULL,
    debtor_iban VARCHAR(34) NOT NULL,
    debtor_name VARCHAR(255) NOT NULL,
    creditor_iban VARCHAR(34) NOT NULL,
    creditor_name VARCHAR(255) NOT NULL,
    creditor_bic VARCHAR(11),
    amount NUMERIC(20, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    remittance_info VARCHAR(280),
    end_to_end_id VARCHAR(64) NOT NULL,
    reject_reason VARCHAR(64),
    reject_detail TEXT,
    submitted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sepa_payments_status ON sepa_payments(status);
CREATE INDEX idx_sepa_payments_debtor_account_id ON sepa_payments(debtor_account_id);
CREATE INDEX idx_sepa_payments_created_at ON sepa_payments(created_at DESC);
