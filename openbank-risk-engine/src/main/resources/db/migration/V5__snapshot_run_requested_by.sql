-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0314 — snapshot runs did not record who asked for them. requested_by is the caller's
-- principal name (SecurityIdentity), nullable so every run created before this migration keeps
-- reading back with no requester rather than a fabricated one.
--
-- Additive only: existing rows are untouched and the column has no NOT NULL / CHECK constraint.

ALTER TABLE snapshot_run ADD COLUMN requested_by VARCHAR(255);

-- Rollback (only while no application code still writes/reads requested_by):
--   ALTER TABLE snapshot_run DROP COLUMN requested_by;
