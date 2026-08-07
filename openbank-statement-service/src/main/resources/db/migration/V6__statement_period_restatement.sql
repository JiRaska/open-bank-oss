-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0035 §D: a correction to a closed period is a NEW period close carrying the next legal
-- sequence and an explicit `supersedes_sequence` back-reference — never an in-place edit.
--
-- V1 made that physically impossible: `ux_statement_period_window` is UNIQUE on
-- (account_id, pocket_currency, period_from, period_to), so a second close for the same window
-- violates the index. Restatement therefore could not be implemented without this migration —
-- `PeriodCloseStatus.SUPERSEDED` had zero write sites fleet-wide (issue #1302 item 5).
--
-- The invariant that actually holds is weaker and is what we index for: **at most one
-- non-SUPERSEDED close per (account, pocket, period)**. Superseded rows stay in the table
-- forever (10y CNB retention on the reproducible record) and remain renderable by legal
-- sequence, so an already-issued statement can still be reproduced byte-for-byte.
--
-- Rollback:
--   DROP INDEX ux_statement_period_window_active;
--   -- only possible once every SUPERSEDED row is removed, since the strict index cannot
--   -- coexist with a restated window:
--   DELETE FROM statement_period WHERE status = 'SUPERSEDED';
--   CREATE UNIQUE INDEX ux_statement_period_window
--       ON statement_period (account_id, pocket_currency, period_from, period_to);

DROP INDEX IF EXISTS ux_statement_period_window;

CREATE UNIQUE INDEX ux_statement_period_window_active
    ON statement_period (account_id, pocket_currency, period_from, period_to)
    WHERE status <> 'SUPERSEDED';

-- Supports the supersession chain lookup (which close replaced which page).
CREATE INDEX ix_statement_period_supersedes
    ON statement_period (account_id, pocket_currency, supersedes_sequence)
    WHERE supersedes_sequence IS NOT NULL;
