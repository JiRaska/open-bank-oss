-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0314 D2 — the run manifest records the model / parameter versions in force when the run was
-- recorded, and the ledger knowledge cut-off (the instant immediately before the ledger read).
--
-- Every column is nullable: a run recorded before this migration keeps reading back with no
-- versions rather than fabricated ones. New runs always write all of them.
--
-- Additive only: existing rows are untouched; no NOT NULL / CHECK constraint, no index.

ALTER TABLE snapshot_run
    ADD COLUMN engine_version            VARCHAR(64),
    ADD COLUMN capital_set_id            VARCHAR(128),
    ADD COLUMN capital_set_version       VARCHAR(64),
    ADD COLUMN liquidity_set_id          VARCHAR(128),
    ADD COLUMN liquidity_set_version     VARCHAR(64),
    ADD COLUMN irrbb_shock_set_version   VARCHAR(64),
    ADD COLUMN irrbb_shock_source        TEXT,
    ADD COLUMN min_reserves_set_id       VARCHAR(128),
    ADD COLUMN min_reserves_set_version  VARCHAR(64),
    ADD COLUMN behavioural_model_id      VARCHAR(128),
    ADD COLUMN behavioural_model_version VARCHAR(64),
    ADD COLUMN ledger_cut_off            TIMESTAMPTZ;

-- Rollback (only while no application code still writes/reads these columns):
--   ALTER TABLE snapshot_run
--       DROP COLUMN engine_version, DROP COLUMN capital_set_id, DROP COLUMN capital_set_version,
--       DROP COLUMN liquidity_set_id, DROP COLUMN liquidity_set_version,
--       DROP COLUMN irrbb_shock_set_version, DROP COLUMN irrbb_shock_source,
--       DROP COLUMN min_reserves_set_id, DROP COLUMN min_reserves_set_version,
--       DROP COLUMN behavioural_model_id, DROP COLUMN behavioural_model_version,
--       DROP COLUMN ledger_cut_off;
