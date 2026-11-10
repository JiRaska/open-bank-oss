-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0096 D3: the year-end result allocation is a maker action on the year close. Record WHO
-- allocated, when, and which journal it posted, on the year-close row itself, so attestation can
-- refuse an attestor who also allocated (four-eyes) — the journal is posted by the system poster
-- and its description is not a control.
--
-- Additive, nullable columns on a small table (one row per fiscal year); online-safe.
-- Rollback (only before any allocation was recorded, or accepting loss of the maker trail):
--   ALTER TABLE ledger_year_close DROP COLUMN allocation_journal_id,
--     DROP COLUMN allocated_at, DROP COLUMN allocated_by;

ALTER TABLE ledger_year_close
    ADD COLUMN allocated_by          VARCHAR(255),
    ADD COLUMN allocated_at          TIMESTAMPTZ,
    ADD COLUMN allocation_journal_id UUID;
