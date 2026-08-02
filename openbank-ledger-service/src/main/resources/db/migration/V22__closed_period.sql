-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0096 D1 / issue #471 — entity-level statutory period close (GL period freeze).
--
-- One row per (period_type, period_from). Lifecycle DRAFT -> FROZEN, enforced in the application
-- layer with a fail-closed hash re-check at freeze time (mirrors ledger_year_close, one
-- granularity down). The trial-balance computation is a read-only aggregation over the existing
-- journal tables -- no schema change there.
--
-- This is what replaces "the trial balance is a read API": /api/v1/journals/trial-balance answers
-- a point-in-time question and changes under you, whereas a FROZEN row is the same numbers made
-- immutable and hash-anchored (zakon 563/1991 Sb. prukaznost/uplnost).
--
-- NEW table only; purely additive and online-safe (no lock on the hot journal tables).

CREATE TABLE ledger_closed_period (
    id            UUID PRIMARY KEY,
    period_type   VARCHAR(8)   NOT NULL
        CONSTRAINT chk_closed_period_type CHECK (period_type IN ('MONTH', 'QUARTER', 'YEAR')),
    period_from   DATE         NOT NULL,
    period_to     DATE         NOT NULL,
    status        VARCHAR(8)   NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_closed_period_status CHECK (status IN ('DRAFT', 'FROZEN')),
    computed_at   TIMESTAMPTZ  NOT NULL,
    total_debits  NUMERIC(20,6) NOT NULL,
    total_credits NUMERIC(20,6) NOT NULL,
    account_count INT          NOT NULL,
    content_hash  VARCHAR(64)  NOT NULL,
    drafted_by    VARCHAR(255),
    frozen_by     VARCHAR(255),
    frozen_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_closed_period UNIQUE (period_type, period_from),
    CONSTRAINT chk_closed_period_range CHECK (period_from <= period_to)
);

-- The posting path asks "is there a FROZEN period containing this entry_date" on every journal
-- (period lock, ADR-0096 D1). A month, its quarter and its year can all be frozen at once, so the
-- query wants the NARROWEST match -- ordering by period_type is not meaningful, so the query
-- orders by (period_to - period_from) and this index serves the range scan that feeds it.
CREATE INDEX idx_closed_period_frozen_range
    ON ledger_closed_period (period_from, period_to)
    WHERE status = 'FROZEN';

-- Rollback:
--   DROP INDEX IF EXISTS idx_closed_period_frozen_range;
--   DROP TABLE IF EXISTS ledger_closed_period;
-- Safe to roll back while the period lock is in shadow mode (openbank.ledger.period-lock.mode,
-- shipped default): nothing refuses a posting on the strength of these rows, and DRAFTs are
-- recomputable from the journal at any time. After a period is FROZEN the row IS the attestation
-- (zakon 563/1991 prukaznost) -- archive the rows first if a rollback is needed past that point.
-- The underlying journal data is unaffected either way.
