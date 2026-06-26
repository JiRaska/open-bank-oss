CREATE TABLE sct_inst_payments (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    status          VARCHAR(32) NOT NULL,
    debtor_account_id UUID NOT NULL,
    debtor_iban     VARCHAR(34) NOT NULL,
    debtor_name     VARCHAR(255) NOT NULL,
    creditor_iban   VARCHAR(34) NOT NULL,
    creditor_name   VARCHAR(255) NOT NULL,
    creditor_bic    VARCHAR(11),
    amount          NUMERIC(20, 6) NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'EUR',
    remittance_info VARCHAR(280),
    end_to_end_id   VARCHAR(64) NOT NULL,
    execution_timeout_at TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    recalled_at     TIMESTAMPTZ,
    recall_reason   VARCHAR(64),
    reject_reason   VARCHAR(64),
    reject_detail   TEXT,
    submitted_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sct_inst_status ON sct_inst_payments(status);
CREATE INDEX idx_sct_inst_debtor ON sct_inst_payments(debtor_account_id);
CREATE INDEX idx_sct_inst_created ON sct_inst_payments(created_at DESC);
CREATE INDEX idx_sct_inst_timeout ON sct_inst_payments(execution_timeout_at) WHERE status = 'PROCESSING';
