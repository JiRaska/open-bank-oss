-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0096 D3: one row per year-end result allocation, keyed by the closing journal's
-- idempotency key. The closing journal is posted by the system poster, so this table is the ONLY
-- attribution of who allocated; rows are never updated, so a year allocated by two principals
-- keeps both and attestation refuses every one of them (four-eyes).
--
-- The row is written in the SAME transaction as the closing journal, under SELECT … FOR UPDATE on
-- the DRAFT ledger_year_close row (which attestation also locks), so a journal exists iff its row
-- does. The `year-end-result:` idempotency prefix is reserved: the generic posting API refuses it.
--
-- Additive, new table; online-safe.
-- Rollback (loses the four-eyes trail of any allocation already made — reverse those journals
-- first): DROP TABLE ledger_year_close_allocation;

CREATE TABLE ledger_year_close_allocation (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    fiscal_year     INT          NOT NULL,
    allocated_by    VARCHAR(255) NOT NULL,
    journal_id      UUID         NOT NULL,
    allocated_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_year_close_allocation_year ON ledger_year_close_allocation (fiscal_year);
