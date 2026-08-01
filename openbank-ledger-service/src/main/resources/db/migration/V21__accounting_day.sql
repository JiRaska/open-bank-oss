-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0207 D2 / issue #1302 item 1+2 — the accounting day as an owned, persisted concept.
--
-- One row per accounting day, lifecycle OPEN -> CUTOFF -> TIED_OUT -> LOCKED. Before this table
-- the only period lock was a fiscal-YEAR check (ledger_year_close), so a journal could be booked
-- into any day of the current year including one already tied out, reconciled and reported --
-- silently invalidating a tie-out computed before it.
--
-- Monotonic progression and the single-step rule are enforced in the domain
-- (AccountingDayRecord.transitionTo); the CHECK below only constrains the value domain. The
-- per-stage timestamps are append-only: an earlier stage's timestamp is never overwritten.
--
-- NEW table only; purely additive and online-safe (no lock on the hot journal tables).

CREATE TABLE ledger_accounting_day (
    id                   UUID PRIMARY KEY,
    business_date        DATE         NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'OPEN'
        CONSTRAINT chk_accounting_day_status CHECK (status IN ('OPEN', 'CUTOFF', 'TIED_OUT', 'LOCKED')),
    opened_at            TIMESTAMPTZ  NOT NULL,
    opened_by            VARCHAR(255) NOT NULL,
    cutoff_at            TIMESTAMPTZ,
    tied_out_at          TIMESTAMPTZ,
    locked_at            TIMESTAMPTZ,
    last_transition_by   VARCHAR(255),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounting_day_business_date UNIQUE (business_date)
);

-- The posting path asks "is THIS day open" on every journal (day lock, ADR-0207 D3). The unique
-- constraint above already provides that lookup; this partial index serves the other hot query --
-- "what is the current open day" for forward-correcting a reversal out of a closed day.
CREATE INDEX idx_accounting_day_open
    ON ledger_accounting_day (business_date DESC)
    WHERE status = 'OPEN';

-- Rollback:
--   DROP INDEX IF EXISTS idx_accounting_day_open;
--   DROP TABLE IF EXISTS ledger_accounting_day;
-- Safe to roll back while the day lock is in shadow mode (openbank.ledger.day-lock.mode=shadow,
-- the shipped default): nothing refuses a posting on the strength of these rows, so dropping them
-- loses measurement, not correctness. After the lock is switched to enforce, a LOCKED row is the
-- evidence that a day was sealed (zakon 563/1991 prukaznost) -- archive the rows before dropping.
