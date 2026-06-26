-- V5: Payment saga orchestration state table
-- Tracks distributed saga state for payment transactions with compensation support.

CREATE TABLE payment_sagas (
    id                  UUID        NOT NULL,
    transaction_id      UUID        NOT NULL,
    state               VARCHAR(32) NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    failure_reason      TEXT,
    compensation_reason TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_payment_sagas PRIMARY KEY (id),
    CONSTRAINT uq_payment_sagas_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_payment_sagas_transaction UNIQUE (transaction_id),
    CONSTRAINT chk_payment_sagas_state CHECK (
        state IN (
            'STARTED',
            'PAYMENT_INITIATED',
            'LEDGER_POSTING',
            'COMPLETED',
            'COMPENSATING',
            'COMPENSATED',
            'FAILED'
        )
    )
);

CREATE INDEX idx_payment_sagas_transaction_id ON payment_sagas (transaction_id);
CREATE INDEX idx_payment_sagas_state ON payment_sagas (state) WHERE state NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED');
