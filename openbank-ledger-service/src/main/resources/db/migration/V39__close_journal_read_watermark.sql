-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Reconciliation must describe the same committed journal population that closes.
-- Legacy runs/snapshots stay unknown; never manufacture historical qualification proof.
-- Rollback: redeploy the previous application, retaining these nullable columns and history.
ALTER TABLE ledger_tieout_runs ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE ledger_tieout_runs ADD COLUMN journal_count BIGINT CHECK (journal_count >= 0);
ALTER TABLE ledger_close_evidence ADD COLUMN journal_count BIGINT CHECK (journal_count >= 0);
