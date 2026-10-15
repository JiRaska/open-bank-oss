-- ADR-0334 slice S3: contributions, unmatched payments, state incentive claims, the incentive
-- ledger and certified tax years.
-- Rollback (no other slice references these tables):
--   DROP TABLE pension_tax_year_certificates; DROP TABLE pension_external_cap_usage;
--   DROP TABLE pension_incentive_ledger; DROP TABLE pension_incentive_claims;
--   DROP TABLE pension_claim_batches; DROP TABLE pension_unmatched_payments;
--   DROP TABLE pension_contributions; DROP TABLE pension_employer_enrolments;
--   DROP TABLE pension_contract_references;
--   DROP SEQUENCE pension_contract_reference_seq;

-- The payment reference a payer quotes (variable-symbol equivalent). Numeric and allocated from a
-- sequence, never derived from the UUID: a hash can collide, and a collision credits a stranger.
CREATE SEQUENCE IF NOT EXISTS pension_contract_reference_seq START WITH 1000000001;

CREATE TABLE pension_contract_references (
    contract_id  UUID PRIMARY KEY REFERENCES pension_contracts (contract_id),
    reference    VARCHAR(20) NOT NULL UNIQUE
);

-- Employers the participant authorised to pay into the contract by bulk file. A bulk line for a
-- contract whose employer is not listed here is parked, never credited (no cross-tenant credit).
CREATE TABLE pension_employer_enrolments (
    contract_id        UUID NOT NULL REFERENCES pension_contracts (contract_id),
    employer_party_id  UUID NOT NULL,
    enrolled_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (contract_id, employer_party_id)
);

-- Append-only. The unique payment_id IS the idempotency guarantee: a redelivered payment hits the
-- index, it is not caught by a read that a concurrent delivery could race past.
CREATE TABLE pension_contributions (
    id                     UUID PRIMARY KEY,
    contract_id            UUID NOT NULL REFERENCES pension_contracts (contract_id),
    payment_id             VARCHAR(128) NOT NULL UNIQUE,
    source                 VARCHAR(16) NOT NULL,
    channel                VARCHAR(24) NOT NULL,
    amount                 NUMERIC(19, 4) NOT NULL,
    currency               CHAR(3) NOT NULL,
    value_date             DATE NOT NULL,
    employer_party_id      UUID,
    subscription_order_id  VARCHAR(128),
    received_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_contributions_positive CHECK (amount > 0 AND amount < 1000000000),
    CONSTRAINT pension_contributions_source_known CHECK (source IN ('PARTICIPANT', 'EMPLOYER', 'STATE', 'TRANSFER_IN')),
    CONSTRAINT pension_contributions_employer_named CHECK ((source = 'EMPLOYER') = (employer_party_id IS NOT NULL))
);

CREATE INDEX idx_pension_contributions_contract_date ON pension_contributions (contract_id, value_date);

CREATE TABLE pension_unmatched_payments (
    id                    UUID PRIMARY KEY,
    payment_id            VARCHAR(128) NOT NULL UNIQUE,
    amount                NUMERIC(19, 4) NOT NULL,
    currency              CHAR(3) NOT NULL,
    value_date            DATE NOT NULL,
    reference             VARCHAR(64),
    channel               VARCHAR(24) NOT NULL,
    payer_account         VARCHAR(64),
    reason                VARCHAR(32) NOT NULL,
    status                VARCHAR(16) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    resolved_contract_id  UUID REFERENCES pension_contracts (contract_id),
    resolved_by           VARCHAR(128),
    resolved_at           TIMESTAMPTZ,
    CONSTRAINT pension_unmatched_status_known CHECK (status IN ('OPEN', 'ASSIGNED', 'RETURNED')),
    CONSTRAINT pension_unmatched_resolution CHECK (status = 'OPEN' OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX idx_pension_unmatched_status ON pension_unmatched_payments (status, created_at);

-- Kept whole as filing evidence: the payload is what was sent, not a re-rendering of it.
CREATE TABLE pension_claim_batches (
    id                 UUID PRIMARY KEY,
    claim_format       VARCHAR(64) NOT NULL,
    period             CHAR(7) NOT NULL,
    claim_ids          TEXT NOT NULL,
    payload            TEXT NOT NULL,
    channel_reference  VARCHAR(128),
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE pension_incentive_claims (
    id                UUID PRIMARY KEY,
    contract_id       UUID NOT NULL REFERENCES pension_contracts (contract_id),
    incentive_id      VARCHAR(64) NOT NULL,
    period            CHAR(7) NOT NULL,
    basis             NUMERIC(19, 4) NOT NULL,
    claimed_amount    NUMERIC(19, 4) NOT NULL,
    currency          CHAR(3) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    batch_id          UUID REFERENCES pension_claim_batches (id),
    received_amount   NUMERIC(19, 4),
    rejection_reason  TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_incentive_claims_once_per_period UNIQUE (contract_id, incentive_id, period),
    CONSTRAINT pension_incentive_claims_status_known CHECK (status IN ('PENDING', 'SUBMITTED', 'RECEIVED', 'REJECTED', 'RETURNED')),
    CONSTRAINT pension_incentive_claims_filed_in_batch CHECK (status = 'PENDING' OR batch_id IS NOT NULL)
);

CREATE INDEX idx_pension_incentive_claims_status ON pension_incentive_claims (status);

-- Append-only clawback ledger: S5 early termination reads the per-incentive balance from here.
CREATE TABLE pension_incentive_ledger (
    id            UUID PRIMARY KEY,
    contract_id   UUID NOT NULL REFERENCES pension_contracts (contract_id),
    incentive_id  VARCHAR(64) NOT NULL,
    claim_id      UUID REFERENCES pension_incentive_claims (id),
    kind          VARCHAR(16) NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    tax_year      INTEGER NOT NULL,
    period        CHAR(7) NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    -- S5 settlement instructions are replayed by Temporal; the key makes each one land once.
    idempotency_key VARCHAR(200) UNIQUE,
    CONSTRAINT pension_incentive_ledger_positive CHECK (amount > 0),
    CONSTRAINT pension_incentive_ledger_kind_known CHECK (kind IN ('RECEIVED', 'RETURNED'))
);

CREATE INDEX idx_pension_incentive_ledger_contract ON pension_incentive_ledger (contract_id, occurred_at);

CREATE TABLE pension_external_cap_usage (
    participant_party_id  UUID NOT NULL,
    tax_year              INTEGER NOT NULL,
    cap_group             VARCHAR(64) NOT NULL,
    amount                NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (participant_party_id, tax_year, cap_group),
    CONSTRAINT pension_external_cap_usage_non_negative CHECK (amount >= 0)
);

-- A certified tax year is frozen: the certificate states these figures and nothing recomputes them.
CREATE TABLE pension_tax_year_certificates (
    contract_id              UUID NOT NULL REFERENCES pension_contracts (contract_id),
    tax_year                 INTEGER NOT NULL,
    summary                  TEXT NOT NULL,
    certificate_document_id  VARCHAR(128) NOT NULL,
    finalized_at             TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (contract_id, tax_year)
);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
