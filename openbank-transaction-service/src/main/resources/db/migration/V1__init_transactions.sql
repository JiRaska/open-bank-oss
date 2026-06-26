CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE transactions (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    reference_number    VARCHAR(50)     NOT NULL,
    type                VARCHAR(20)     NOT NULL,
    source_account_id   UUID,
    target_account_id   UUID,
    amount              NUMERIC(20,6)   NOT NULL,
    currency_code       CHAR(3)         NOT NULL,
    fx_rate             NUMERIC(20,10),
    base_amount         NUMERIC(20,6)   NOT NULL,
    base_currency_code  CHAR(3)         NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    description         VARCHAR(500),
    value_date          DATE            NOT NULL,
    booking_date        DATE            NOT NULL,
    initiated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    failure_reason      VARCHAR(500),
    idempotency_key     VARCHAR(100)    NOT NULL,
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_transactions PRIMARY KEY (id, booking_date),
    CONSTRAINT uq_transactions_reference UNIQUE (reference_number, booking_date),
    CONSTRAINT uq_transactions_idempotency UNIQUE (idempotency_key, booking_date),
    CONSTRAINT chk_transactions_type CHECK (type IN (
        'DEBIT','CREDIT','TRANSFER','FEE','INTEREST','REVERSAL','ADJUSTMENT'
    )),
    CONSTRAINT chk_transactions_status CHECK (status IN (
        'PENDING','PROCESSING','COMPLETED','FAILED','REVERSED'
    )),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0)
) PARTITION BY RANGE (booking_date);

CREATE TABLE transactions_2025 PARTITION OF transactions
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE transactions_2026 PARTITION OF transactions
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE transactions_default PARTITION OF transactions DEFAULT;

CREATE INDEX idx_transactions_source_account  ON transactions(source_account_id) WHERE source_account_id IS NOT NULL;
CREATE INDEX idx_transactions_target_account  ON transactions(target_account_id) WHERE target_account_id IS NOT NULL;
CREATE INDEX idx_transactions_status          ON transactions(status);
CREATE INDEX idx_transactions_booking_date    ON transactions(booking_date DESC);
CREATE INDEX idx_transactions_initiated_at    ON transactions(initiated_at DESC);
