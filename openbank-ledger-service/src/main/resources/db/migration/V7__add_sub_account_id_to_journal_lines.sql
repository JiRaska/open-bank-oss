-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0039 Phase B — sub-ledger (analytická evidence) dimension on journal lines.
--
-- The general ledger keeps customer deposits on per-currency CONTROL accounts (2100=CZK,
-- 2101=EUR, 2102=USD, 2103=GBP). CNB accounting rules (zákon 563/1991 Sb. + vyhláška 501/2002 Sb.)
-- require that a control account tie out against a per-customer analytical sub-ledger. We add an
-- OPTIONAL sub_account_id to journal lines: it is populated ONLY on deposit-control legs (enforced
-- in the application layer) and names the customer account the leg belongs to. All other legs
-- (cash-clearing, FX position, P&L) leave it NULL.
--
-- Nullable + no backfill ⇒ this is a backward-compatible, online-safe column addition. Existing
-- rows keep NULL; only postings made after the producer (transaction-service) starts stamping the
-- dimension will carry it. The partial index supports per-account sub-ledger tie-out queries.

ALTER TABLE journal_lines ADD COLUMN sub_account_id UUID;

CREATE INDEX idx_journal_lines_sub_account
    ON journal_lines (sub_account_id, base_currency)
    WHERE sub_account_id IS NOT NULL;

-- Rollback:
--   DROP INDEX IF EXISTS idx_journal_lines_sub_account;
--   ALTER TABLE journal_lines DROP COLUMN IF EXISTS sub_account_id;
-- Safe to roll back: the column is nullable and unreferenced by constraints; dropping it loses only
-- the analytical dimension on already-posted lines (the GL control balances are unaffected).
