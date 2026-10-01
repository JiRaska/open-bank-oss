-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- #11107 — the instrument keeps its source contract's human reference (a loan's contract number
-- from lending, UV-YYYY-NNNNNN) so the console can show it instead of a UUID prefix.
--
-- Nullable and additive: instruments of runs recorded before this migration keep reading back with
-- no number rather than a fabricated one; other instrument kinds have none. Display only — it is not
-- part of the run's input hash. (V7 is claimed by open PR #11549.)
--
-- Rollback: ALTER TABLE snapshot_instrument DROP COLUMN contract_number;

ALTER TABLE snapshot_instrument ADD COLUMN contract_number VARCHAR(32);
