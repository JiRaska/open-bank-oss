-- ADR-0334 slice S1: participant-side pension contracts and their strategy election history.
-- Rollback:
--   DROP TABLE pension_strategy_elections; DROP TABLE pension_contracts;
--   DROP SEQUENCE pension_strategy_elections_seq; DROP SEQUENCE pension_contracts_seq;

CREATE TABLE pension_contracts (
    id                             BIGSERIAL PRIMARY KEY,
    contract_id                    UUID NOT NULL UNIQUE,
    participant_party_id           UUID NOT NULL,
    product_line                   VARCHAR(16) NOT NULL,
    jurisdiction                   VARCHAR(8) NOT NULL,
    -- The pack version is PINNED: a contract is judged by the law it was sold under (ADR-0212 D3).
    pack_version                   INTEGER NOT NULL,
    provider_entity_id             UUID NOT NULL,
    provider_type                  VARCHAR(32) NOT NULL,
    participant_birth_date         DATE NOT NULL,
    status                         VARCHAR(24) NOT NULL,
    contribution_amount            NUMERIC(19, 4) NOT NULL,
    employer_contribution_amount   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    contribution_currency          CHAR(3) NOT NULL,
    contribution_frequency         VARCHAR(16) NOT NULL,
    beneficiaries                  TEXT NOT NULL DEFAULT '[]',
    start_date                     DATE,
    -- Client-supplied key that makes a retried create a no-op (unique per participant below).
    idempotency_key                VARCHAR(256),
    created_at                     TIMESTAMPTZ NOT NULL,
    updated_at                     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_contracts_status_known CHECK (status IN (
        'DRAFT', 'PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'TERMINATING',
        'PAID_OUT', 'TRANSFERRED_OUT', 'CLOSED')),
    -- Mirrors the aggregate's init block: an activated contract always carries its start date.
    CONSTRAINT pension_contracts_start_date_when_active CHECK (
        status IN ('DRAFT', 'PENDING_ACTIVATION') OR start_date IS NOT NULL)
);

CREATE UNIQUE INDEX uq_pension_contracts_idempotency
    ON pension_contracts (participant_party_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_pension_contracts_participant ON pension_contracts (participant_party_id, status);

-- Append-only: a strategy change adds a row, it never rewrites the previous election.
CREATE TABLE pension_strategy_elections (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     UUID NOT NULL REFERENCES pension_contracts (contract_id),
    strategy_code   VARCHAR(64) NOT NULL,
    effective_from  DATE NOT NULL,
    elected_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pension_strategy_elections_contract ON pension_strategy_elections (contract_id, id);

CREATE SEQUENCE IF NOT EXISTS pension_contracts_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_strategy_elections_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
