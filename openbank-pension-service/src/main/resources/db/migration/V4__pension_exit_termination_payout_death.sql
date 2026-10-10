-- ADR-0334 slice S5: early termination, regular payout / partial withdrawal, death claims, and the
-- payment-instruction ledger that makes every money movement idempotent.
-- Rollback:
--   DROP TABLE pension_payment_instructions; DROP TABLE pension_death_claims;
--   DROP TABLE pension_payout_requests; DROP TABLE pension_termination_notices;
--   DROP SEQUENCE pension_payment_instructions_seq; DROP SEQUENCE pension_death_claims_seq;
--   DROP SEQUENCE pension_payout_requests_seq; DROP SEQUENCE pension_termination_notices_seq;
-- No existing table is altered, so the rollback loses only S5 data.

-- One row per aggregate: queried columns are real columns, the remainder is the JSON body.
CREATE TABLE pension_termination_notices (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_id     UUID NOT NULL UNIQUE,
    contract_id      UUID NOT NULL REFERENCES pension_contracts (contract_id),
    status           VARCHAR(16) NOT NULL,
    idempotency_key  VARCHAR(128),
    body             TEXT NOT NULL,
    row_version      INTEGER NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_termination_notices_status_known CHECK (status IN (
        'QUOTED', 'SIGNED', 'REDEEMED', 'PAID', 'COMPLETED', 'EXPIRED', 'SUPERSEDED'))
);
CREATE INDEX idx_pension_termination_notices_contract ON pension_termination_notices (contract_id, status);
-- A signature replayed with the same key finds the same notice; never two notices per key.
CREATE UNIQUE INDEX uq_pension_termination_notices_key
    ON pension_termination_notices (contract_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE pension_payout_requests (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_id     UUID NOT NULL UNIQUE,
    contract_id      UUID NOT NULL REFERENCES pension_contracts (contract_id),
    status           VARCHAR(16) NOT NULL,
    idempotency_key  VARCHAR(128),
    body             TEXT NOT NULL,
    row_version      INTEGER NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_payout_requests_status_known CHECK (status IN (
        'QUOTED', 'CONFIRMED', 'IN_PAYMENT', 'COMPLETED', 'EXPIRED'))
);
CREATE INDEX idx_pension_payout_requests_contract ON pension_payout_requests (contract_id, status);
CREATE INDEX idx_pension_payout_requests_status ON pension_payout_requests (status);
CREATE UNIQUE INDEX uq_pension_payout_requests_key
    ON pension_payout_requests (contract_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- contract_id is UNIQUE: a participant dies once, so a contract has at most one death claim.
CREATE TABLE pension_death_claims (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_id     UUID NOT NULL UNIQUE,
    contract_id      UUID NOT NULL UNIQUE REFERENCES pension_contracts (contract_id),
    status           VARCHAR(16) NOT NULL,
    idempotency_key  VARCHAR(128),
    body             TEXT NOT NULL,
    row_version      INTEGER NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_death_claims_status_known CHECK (status IN (
        'NOTIFIED', 'APPROVED', 'IN_PAYMENT', 'SETTLED'))
);

-- Every money movement out of a contract, unique by its deterministic key: a retried activity
-- finds its own earlier instruction instead of creating a second one.
CREATE TABLE pension_payment_instructions (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  VARCHAR(200) NOT NULL UNIQUE,
    contract_id      UUID NOT NULL REFERENCES pension_contracts (contract_id),
    purpose          VARCHAR(64) NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency         CHAR(3) NOT NULL,
    creditor_iban    VARCHAR(34) NOT NULL,
    status           VARCHAR(8) NOT NULL CHECK (status IN ('PENDING', 'SENT')),
    payment_ref      VARCHAR(128),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_payment_instructions_ref_when_sent CHECK (status = 'PENDING' OR payment_ref IS NOT NULL)
);
CREATE INDEX idx_pension_payment_instructions_contract ON pension_payment_instructions (contract_id);

CREATE SEQUENCE IF NOT EXISTS pension_termination_notices_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_payout_requests_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_death_claims_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_payment_instructions_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
