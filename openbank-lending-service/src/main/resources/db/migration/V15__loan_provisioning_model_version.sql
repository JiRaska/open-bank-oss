-- SPDX-License-Identifier: Apache-2.0
-- PD/LGD calibration governance (issue #8364): persist the risk-parameter model version on every
-- IFRS 9 provisioning record, so an ECL figure is always traceable to the exact parameter set that
-- produced it. Existing rows predate versioning and were all computed by the one source that ever
-- ran against them — ConservativeRiskParameterSource, version "noop-flat-v1" — so the backfill
-- default is fact, not approximation.
--
-- Rollback: ALTER TABLE loan_provisioning DROP COLUMN model_version;

ALTER TABLE loan_provisioning
    ADD COLUMN model_version TEXT NOT NULL DEFAULT 'noop-flat-v1';

-- The default exists only to backfill existing rows; new writes always set the column explicitly.
ALTER TABLE loan_provisioning
    ALTER COLUMN model_version DROP DEFAULT;
