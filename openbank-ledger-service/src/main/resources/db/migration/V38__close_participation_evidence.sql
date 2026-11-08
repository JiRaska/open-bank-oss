-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- ADR-0342 / #13167: preserve what was known AT close, before late reports can change it.
ALTER TABLE ledger_close_participation
    ADD COLUMN exercised_jobs TEXT[] NOT NULL DEFAULT '{}'::text[]
    CHECK (cardinality(exercised_jobs) <= 16);
CREATE TABLE ledger_close_evidence (
    business_date DATE PRIMARY KEY REFERENCES ledger_accounting_day (business_date),
    observed_at TIMESTAMPTZ NOT NULL,
    tie_out_run_id UUID,
    expected_jobs JSONB NOT NULL CHECK (jsonb_typeof(expected_jobs) = 'object'),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('off', 'shadow', 'enforce')),
    expected_principals JSONB NOT NULL CHECK (jsonb_typeof(expected_principals) = 'object'),
    reports JSONB NOT NULL CHECK (jsonb_typeof(reports) = 'array')
);
-- No backfill: current reports cannot reconstruct what was known at a historical close.
-- Rollback: revert the image; retain this additive table and its evidence. Never drop history.
