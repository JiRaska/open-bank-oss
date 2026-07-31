-- SPDX-License-Identifier: Apache-2.0
-- ADR-0215 termination lifecycle: termination sub-lifecycle states on loan_status,
-- the bank-termination four-eyes columns on loan, the persisted settlement quote
-- (binding until valid_until — settlement against an expired quote is refused,
-- ADR-0215 D2), and collateral release tracking (released with the closure, D5).
--
-- Rollback: DROP TABLE settlement_quote;
--           ALTER TABLE loan DROP COLUMN notice_ends_on, DROP COLUMN terminated_by,
--             DROP COLUMN terminated_at;
--           ALTER TABLE collateral DROP COLUMN released_at;
--           (PG enum values cannot be dropped — they stay inert.)

ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'DELINQUENT';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'DEFAULTED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'FORBEARANCE_ASSESSED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'TERMINATION_NOTICED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'ACCELERATED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'EARLY_REPAYMENT_REQUESTED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'SETTLEMENT_QUOTED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'SETTLED';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'WITHDRAWN';
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'UNWOUND';

ALTER TABLE loan
    ADD COLUMN notice_ends_on DATE,
    ADD COLUMN terminated_by  VARCHAR(128),
    ADD COLUMN terminated_at  TIMESTAMPTZ;

CREATE TABLE settlement_quote (
    id                    uuid         PRIMARY KEY,
    loan_id               uuid         NOT NULL REFERENCES loan (id),
    as_of_date            DATE         NOT NULL,
    valid_until           DATE         NOT NULL,
    outstanding_principal NUMERIC(20,2) NOT NULL,
    accrued_interest      NUMERIC(20,2) NOT NULL,
    compensation          NUMERIC(20,2) NOT NULL,
    unapplied_credit      NUMERIC(20,2) NOT NULL DEFAULT 0,
    total                 NUMERIC(20,2) NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    settled_at            TIMESTAMPTZ
);

CREATE INDEX idx_settlement_quote_loan ON settlement_quote(loan_id);

ALTER TABLE collateral
    ADD COLUMN released_at TIMESTAMPTZ;
