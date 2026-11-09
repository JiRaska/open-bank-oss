-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Stable source receipts make retries idempotent without trusting caller timestamps.
-- Legacy reports remain valid, append-only receipts with unknown source identity.
-- Rollback: retain the column/index and history, redeploy the previous application.
ALTER TABLE ledger_close_participation ADD COLUMN source_run_id VARCHAR(64)
    CHECK (source_run_id IS NULL OR source_run_id ~ '^[a-f0-9]{64}$');
CREATE UNIQUE INDEX uq_close_participation_source_run
    ON ledger_close_participation (business_date, participant, source_run_id)
    WHERE source_run_id IS NOT NULL;
-- Count alone cannot distinguish archive removal plus the same number of new journals.
-- This migration is still part of the unreleased expansion; no historical fingerprint is inferred.
ALTER TABLE ledger_tieout_runs ADD COLUMN journal_fingerprint VARCHAR(64);
ALTER TABLE ledger_close_evidence ADD COLUMN journal_fingerprint VARCHAR(64);
