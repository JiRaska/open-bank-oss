-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0069 D3 / issue #470 — statement close-run hardening before enabling the monthly cron.
--
-- The scheduled monthly close (PeriodCloseScheduler) previously swallowed per-account failures
-- (recoverWithItem { emptyList() }) and persisted nothing about the run itself, so an operator
-- had no way to see whether a cron fired, how many pockets closed, or which ones failed and why.
-- Before flipping openbank.statement.scheduled-close.enabled=true we need a durable, queryable
-- record of every close run and every failure within it.
--
-- A period-close itself remains the only sequenced/legal artefact (statement_period). These two
-- tables are operational telemetry: they capture the OUTCOME of a run, not statement content.
-- A FAILED pocket is NOT a statement_period row (a period exists only when it closes cleanly);
-- the failure is recorded here so the catch-up run can retry it next cadence.
--
-- Rollback: DROP TABLE statement_close_failure; DROP TABLE statement_close_run;

CREATE TABLE statement_close_run (
    id                      UUID            PRIMARY KEY,
    -- SCHEDULED (cron) or MANUAL (operator-triggered retry).
    trigger                 VARCHAR(16)     NOT NULL,
    -- RUNNING -> COMPLETED | COMPLETED_WITH_FAILURES.
    status                  VARCHAR(24)     NOT NULL DEFAULT 'RUNNING',
    -- The [from,to] month window the run targeted (the most recent month it closes through).
    period_from             DATE,
    period_to               DATE,
    accounts_enumerated     INTEGER         NOT NULL DEFAULT 0,
    pockets_closed          INTEGER         NOT NULL DEFAULT 0,
    pockets_failed          INTEGER         NOT NULL DEFAULT 0,
    pockets_skipped         INTEGER         NOT NULL DEFAULT 0,
    started_at              TIMESTAMPTZ     NOT NULL,
    finished_at             TIMESTAMPTZ
);

-- Operator surface reads "the latest run" and recent history.
CREATE INDEX ix_statement_close_run_started
    ON statement_close_run (started_at DESC);

CREATE TABLE statement_close_failure (
    id                      UUID            PRIMARY KEY,
    run_id                  UUID            NOT NULL REFERENCES statement_close_run (id) ON DELETE CASCADE,
    account_id              UUID            NOT NULL,
    pocket_currency         VARCHAR(3)      NOT NULL,
    period_from             DATE            NOT NULL,
    period_to               DATE            NOT NULL,
    -- RECONCILIATION (fail-closed mismatch, ADR-0035 §E), UPSTREAM (a dependent read failed),
    -- or UNKNOWN (any other error). Drives whether a retry is likely to succeed.
    reason                  VARCHAR(32)     NOT NULL,
    detail                  TEXT,
    failed_at               TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_statement_close_failure_run
    ON statement_close_failure (run_id);

-- A pocket that keeps failing across runs is the alert signal; query by (account, pocket).
CREATE INDEX ix_statement_close_failure_pocket
    ON statement_close_failure (account_id, pocket_currency, failed_at DESC);
