-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0028 D3 — the leaf GL accounts openbank-lending-service's LendingJournalFactory posts
-- against. LendingLedgerConfig.Gl documents its defaults as "stable placeholder UUIDs (the
-- `a0000000-…` family the ledger seeds)", but only `fundingClearing` (1100, 'Customer Cash
-- Clearing', V3) actually existed — every other default (loans-receivable 1200,
-- interest-receivable 1300, interest-income 4100, loan-loss-expense 5100, loan-loss-allowance
-- 1400) was never seeded here. Any real lending posting using the defaults (DISBURSEMENT,
-- PRINCIPAL_REPAYMENT, INTEREST*, WRITE_OFF*, RESCHEDULE_FORGIVENESS, PROVISIONING) 422s with
-- "GL account not found" — caught by the nightly full-fleet build once #1623 started deriving the
-- lending consumer pact body from the real factory + config defaults (issue #1720): the DISBURSEMENT
-- interaction (DEBIT loans-receivable / CREDIT funding-clearing) failed provider verification
-- because loans-receivable (1200) didn't exist.
--
-- Types/currency follow the V17 interest-capitalization-accounts convention: CZK only (lending is
-- single-currency per LendingJournalFactory's kdoc — "Loans are single-currency, so every entry is
-- two-legged and self-balances within that currency"), stable UUID = 'a0000000-…' + zero-padded
-- code, loan-loss-allowance kept ASSET (contra-asset carried as a negative-balance asset, same
-- convention CreditRiskPorts.kt documents).

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000001200', '1200', 'Loans Receivable', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001300', '1300', 'Interest Receivable', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001400', '1400', 'Loan Loss Allowance', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004100', '4100', 'Interest Income', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005100', '5100', 'Loan Loss Expense', 'EXPENSE', 'CZK', true, true);

-- Rollback:
--   DELETE FROM gl_accounts WHERE id IN (
--       'a0000000-0000-0000-0000-000000001200',
--       'a0000000-0000-0000-0000-000000001300',
--       'a0000000-0000-0000-0000-000000001400',
--       'a0000000-0000-0000-0000-000000004100',
--       'a0000000-0000-0000-0000-000000005100');
-- Safe to roll back only before any journal_lines reference these accounts (the FK would block the
-- DELETE otherwise) — i.e. before openbank-lending-service posts a real disbursement/repayment/
-- write-off/provisioning entry in a live environment.
