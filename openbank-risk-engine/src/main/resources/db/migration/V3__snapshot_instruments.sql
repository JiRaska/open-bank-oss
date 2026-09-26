-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0314 D4 — the canonical instrument model, first slice: loans from lending's loan book enter
-- the snapshot as contract-level instruments, tied out to the ledger on Loans Receivable.
--
-- snapshot_position gains the LOAN kind and the instrument it came from. snapshot_instrument holds
-- the common core (explicit column names, one per field) plus the loan extension's scalar fields;
-- snapshot_instrument_installment holds the loan's remaining contractual schedule — what a FIXED
-- loan's cash flows are, and so what reproducing a run's flows needs.
--
-- Additive only: existing runs keep their rows and read back with no instruments.

ALTER TABLE snapshot_position DROP CONSTRAINT snapshot_position_position_kind_check;
ALTER TABLE snapshot_position DROP CONSTRAINT ck_snapshot_position_kind_shape;
ALTER TABLE snapshot_position ADD COLUMN instrument_id VARCHAR(64);
ALTER TABLE snapshot_position ADD CONSTRAINT ck_snapshot_position_kind
    CHECK (position_kind IN ('SUB_LEDGER', 'GL_ACCOUNT', 'LOAN'));
ALTER TABLE snapshot_position ADD CONSTRAINT ck_snapshot_position_kind_shape CHECK (
    (position_kind = 'SUB_LEDGER' AND sub_account_id IS NOT NULL AND instrument_id IS NULL)
    OR (position_kind = 'GL_ACCOUNT' AND sub_account_id IS NULL AND gl_account_code IS NOT NULL
        AND instrument_id IS NULL)
    OR (position_kind = 'LOAN' AND sub_account_id IS NULL AND instrument_id IS NOT NULL)
);

CREATE TABLE snapshot_instrument (
    run_id                  UUID NOT NULL REFERENCES snapshot_run (id),
    instrument_id           VARCHAR(64) NOT NULL,
    instrument_kind         VARCHAR(32) NOT NULL CHECK (instrument_kind IN (
        'AMORTISING_LOAN', 'BULLET', 'NON_MATURITY_DEPOSIT', 'TERM_DEPOSIT', 'CURRENT_ACCOUNT',
        'FX_POSITION', 'CASH_NOSTRO', 'BOND', 'MONEY_MARKET_DEAL', 'DERIVATIVE_LEG', 'EQUITY_CAPITAL')),
    gl_account_code         VARCHAR(32),
    currency                CHAR(3) NOT NULL,
    -- Unconstrained NUMERIC, trial-balance sign (debit - credit): the tie-out is exact.
    outstanding             NUMERIC NOT NULL,
    value_date              DATE,
    maturity_date           DATE,
    rate_type               VARCHAR(8) CHECK (rate_type IN ('FIXED', 'FLOATING')),
    current_annual_rate     NUMERIC,
    rate_index              VARCHAR(16),
    spread                  NUMERIC,
    reset_frequency_months  INTEGER,
    next_reset_date         DATE,
    counterparty_ref        VARCHAR(64),
    ifrs9_stage             VARCHAR(16),
    -- Loan extension (NULL for any other kind).
    amortization_method     VARCHAR(16),
    periods_per_year        INTEGER,
    PRIMARY KEY (run_id, instrument_id)
);

CREATE TABLE snapshot_instrument_installment (
    run_id              UUID NOT NULL,
    instrument_id       VARCHAR(64) NOT NULL,
    installment_number  INTEGER NOT NULL,
    due_date            DATE NOT NULL,
    principal           NUMERIC NOT NULL,
    interest            NUMERIC NOT NULL,
    PRIMARY KEY (run_id, instrument_id, installment_number),
    FOREIGN KEY (run_id, instrument_id) REFERENCES snapshot_instrument (run_id, instrument_id)
);

-- Rollback (only while no run has stored a LOAN position):
--   DROP TABLE snapshot_instrument_installment;
--   DROP TABLE snapshot_instrument;
--   ALTER TABLE snapshot_position DROP CONSTRAINT ck_snapshot_position_kind_shape;
--   ALTER TABLE snapshot_position DROP CONSTRAINT ck_snapshot_position_kind;
--   ALTER TABLE snapshot_position DROP COLUMN instrument_id;
--   ALTER TABLE snapshot_position ADD CONSTRAINT snapshot_position_position_kind_check
--       CHECK (position_kind IN ('SUB_LEDGER', 'GL_ACCOUNT'));
--   ALTER TABLE snapshot_position ADD CONSTRAINT ck_snapshot_position_kind_shape CHECK (
--       (position_kind = 'SUB_LEDGER' AND sub_account_id IS NOT NULL)
--       OR (position_kind = 'GL_ACCOUNT' AND sub_account_id IS NULL AND gl_account_code IS NOT NULL));
