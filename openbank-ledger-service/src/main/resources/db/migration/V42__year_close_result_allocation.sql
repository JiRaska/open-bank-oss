-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0096 D3: every year-end result allocation, one row per closing-journal idempotency key.
--
-- The closing journal is posted by the system poster, so this table is the ONLY attribution of
-- who allocated. It is append-and-complete, never overwritten: a year allocated twice by two
-- principals keeps both, and attestation refuses every one of them (four-eyes).
--
-- Write order is PENDING first, then the journal, then POSTED. The journal posting owns its own
-- transaction (locks, idempotency replay recovery), so the two cannot share one; instead the
-- attribution is committed BEFORE the money moves. A crash after the PENDING row is recovered
-- deterministically by idempotency key: a journal under that key ⇒ POSTED, none ⇒ ABANDONED.
-- Attestation refuses a year with a PENDING row or with any `year-end-result:{year}:*` journal
-- that no POSTED row attributes.
--
-- The partial unique index admits one PENDING allocation per year, so two concurrent
-- allocations cannot both start.
--
-- Additive, new table; online-safe.
-- Rollback (loses the four-eyes trail of any allocation already made — reverse those journals
-- first): DROP TABLE ledger_year_close_allocation;

CREATE TABLE ledger_year_close_allocation (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    fiscal_year     INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL
        CONSTRAINT chk_year_close_allocation_status CHECK (status IN ('PENDING', 'POSTED', 'ABANDONED')),
    allocated_by    VARCHAR(255) NOT NULL,
    requested_at    TIMESTAMPTZ  NOT NULL,
    journal_id      UUID,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_year_close_allocation_posted CHECK (status <> 'POSTED' OR journal_id IS NOT NULL)
);

CREATE INDEX idx_year_close_allocation_year ON ledger_year_close_allocation (fiscal_year);
CREATE UNIQUE INDEX uq_year_close_allocation_one_pending
    ON ledger_year_close_allocation (fiscal_year) WHERE status = 'PENDING';
