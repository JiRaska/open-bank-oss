CREATE TABLE IF NOT EXISTS standing_orders (
    id                   UUID PRIMARY KEY,
    idempotency_key      VARCHAR(255) NOT NULL UNIQUE,
    party_id             UUID NOT NULL,
    debit_account_id     UUID NOT NULL,
    creditor_iban        VARCHAR(34) NOT NULL,
    creditor_name        VARCHAR(140) NOT NULL,
    creditor_bic         VARCHAR(11),
    amount_minor_units   BIGINT NOT NULL,
    currency             CHAR(3) NOT NULL,
    frequency            VARCHAR(20) NOT NULL,
    payment_type         VARCHAR(20) NOT NULL DEFAULT 'SEPA_CREDIT',
    remittance_info      VARCHAR(140),
    start_date           DATE NOT NULL,
    end_date             DATE,
    next_execution_date  DATE NOT NULL,
    last_execution_date  DATE,
    execution_count      INT NOT NULL DEFAULT 0,
    failure_count        INT NOT NULL DEFAULT 0,
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_so_party_id   ON standing_orders(party_id);
CREATE INDEX idx_so_account_id ON standing_orders(debit_account_id);
CREATE INDEX idx_so_next_exec  ON standing_orders(next_execution_date, status);
