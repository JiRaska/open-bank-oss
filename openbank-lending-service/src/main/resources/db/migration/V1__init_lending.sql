-- SPDX-License-Identifier: MPL-2.0
-- Lending / credit bounded context (ADR-0028): origination, servicing, collateral, IFRS 9 provisioning.

CREATE TYPE amortization_method AS ENUM ('ANNUITY', 'EQUAL_PRINCIPAL', 'BULLET');
CREATE TYPE application_status  AS ENUM ('PROPOSED', 'APPROVED', 'REJECTED', 'DISBURSED');
CREATE TYPE loan_status         AS ENUM ('ACTIVE', 'CLOSED', 'WRITTEN_OFF');
CREATE TYPE collateral_type     AS ENUM ('REAL_ESTATE', 'VEHICLE', 'SECURITIES', 'CASH_DEPOSIT', 'GUARANTEE', 'OTHER');

-- Origination: a credit application moving through the four-eyes decision flow (EBA/GL/2020/06).
CREATE TABLE loan_application (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id            UUID NOT NULL,
    requested_amount    NUMERIC(20,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    nominal_annual_rate NUMERIC(10,6) NOT NULL,
    term_periods        INTEGER NOT NULL,
    periods_per_year    INTEGER NOT NULL DEFAULT 12,
    method              amortization_method NOT NULL DEFAULT 'ANNUITY',
    first_due_date      DATE NOT NULL,
    status              application_status NOT NULL DEFAULT 'PROPOSED',
    proposed_by         VARCHAR(128) NOT NULL,                  -- maker
    decided_by          VARCHAR(128),                           -- checker (must differ from maker)
    decision_reason     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decided_at          TIMESTAMPTZ
);

-- Servicing: the live loan booked from an approved, disbursed application.
CREATE TABLE loan (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID NOT NULL REFERENCES loan_application(id),
    party_id            UUID NOT NULL,
    principal           NUMERIC(20,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    nominal_annual_rate NUMERIC(10,6) NOT NULL,
    term_periods        INTEGER NOT NULL,
    periods_per_year    INTEGER NOT NULL DEFAULT 12,
    method              amortization_method NOT NULL,
    first_due_date      DATE NOT NULL,
    status              loan_status NOT NULL DEFAULT 'ACTIVE',
    disbursed_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             BIGINT NOT NULL DEFAULT 0,              -- optimistic lock; emitted to outbox for ADR-0026 reconciliation
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Servicing: the contractual repayment schedule (one row per installment).
CREATE TABLE installment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id             UUID NOT NULL REFERENCES loan(id),
    number              INTEGER NOT NULL,
    due_date            DATE NOT NULL,
    currency            CHAR(3) NOT NULL,
    opening_balance     NUMERIC(20,2) NOT NULL,
    principal           NUMERIC(20,2) NOT NULL,
    interest            NUMERIC(20,2) NOT NULL,
    payment             NUMERIC(20,2) NOT NULL,
    closing_balance     NUMERIC(20,2) NOT NULL,
    paid                BOOLEAN NOT NULL DEFAULT FALSE,
    paid_at             TIMESTAMPTZ,
    UNIQUE(loan_id, number)
);

-- Collateral: security registered against a loan (AnaCredit protection data).
CREATE TABLE collateral (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id             UUID NOT NULL REFERENCES loan(id),
    type                collateral_type NOT NULL,
    description         TEXT,
    market_value        NUMERIC(20,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    haircut             NUMERIC(5,4) NOT NULL DEFAULT 0,        -- [0,1] regulatory/risk haircut
    valued_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Transactional outbox (ADR-0003): every cash event is a ledger posting published from here.
CREATE TABLE lending_outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_application_party   ON loan_application(party_id);
CREATE INDEX idx_loan_application_status  ON loan_application(status);
CREATE INDEX idx_loan_party               ON loan(party_id);
CREATE INDEX idx_loan_status              ON loan(status);
CREATE INDEX idx_installment_loan         ON installment(loan_id);
CREATE INDEX idx_installment_due          ON installment(due_date) WHERE paid = FALSE;
CREATE INDEX idx_collateral_loan          ON collateral(loan_id);
CREATE INDEX idx_lending_outbox_status    ON lending_outbox(status, created_at ASC);
CREATE INDEX idx_lending_outbox_aggregate ON lending_outbox(aggregate_id);
