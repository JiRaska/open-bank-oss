-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0039 Phase B hardening — persist every sub-ledger tie-out run.
--
-- The 06:00 tie-out previously left no durable record: a break only incremented a counter
-- and logged ERROR, and a run that failed outright (DB glitch at 06:00) left NOTHING — the
-- exact silent-missing-control failure mode balance-service hit in issue #855 (41 unnoticed
-- days). One row per run makes both cases provable from data: a break is a recorded incident,
-- and the ABSENCE of a fresh row is what TieOutFreshnessWatchdog escalates.
--
-- NEW table only; purely additive and online-safe (no lock on hot journal tables).

CREATE TABLE ledger_tieout_runs (
    id               UUID PRIMARY KEY,
    as_of            DATE         NOT NULL,
    run_at           TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(16)  NOT NULL
        CONSTRAINT chk_tieout_run_status CHECK (status IN ('OK', 'BREAK', 'ERROR')),
    accounts_checked INT          NOT NULL,
    breaks           INT          NOT NULL,
    errors           INT          NOT NULL
);

-- The watchdog reads "latest run" every hour; the day-end operator view reads recent history.
CREATE INDEX idx_tieout_runs_run_at ON ledger_tieout_runs (run_at DESC);
