-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Expand only.
-- Rollback: disable the producer; retain bindings, receipts, proposals and financial journals.
CREATE TABLE ledger_book_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    book_kind VARCHAR(32) NOT NULL CHECK (book_kind IN ('PENSION_COMPANY', 'PENSION_FUND')),
    book_id UUID NOT NULL
);
CREATE TABLE ledger_pension_fee_receipts (
    nav_id UUID PRIMARY KEY,
    fund_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    posting_date DATE NOT NULL,
    journal_id UUID,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PENDING', 'POSTED', 'NO_POSTING')),
    synthetic BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK ((outcome = 'PENDING') = (completed_at IS NULL)),
    CHECK ((outcome = 'POSTED') = (journal_id IS NOT NULL))
);
ALTER TABLE ledger_tieout_runs ADD COLUMN control_kind VARCHAR(32) NOT NULL DEFAULT 'BANK';
ALTER TABLE ledger_close_evidence ADD COLUMN book_kind VARCHAR(32) NOT NULL DEFAULT 'BANK';
ALTER TABLE ledger_close_evidence ADD COLUMN book_id UUID;
ALTER TABLE ledger_tieout_runs ADD CONSTRAINT ck_ledger_tieout_control_kind
    CHECK (control_kind IN ('BANK', 'PENSION_COMPANY', 'PENSION_FUND'));
CREATE TABLE ledger_pension_reconciliations (
    id UUID PRIMARY KEY,
    request_key VARCHAR(512) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    source_references TEXT NOT NULL,
    expected_balances TEXT NOT NULL,
    proposed_by VARCHAR(200) NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by VARCHAR(200),
    approved_at TIMESTAMPTZ,
    CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CHECK (approved_by IS NULL OR approved_by <> proposed_by)
);
