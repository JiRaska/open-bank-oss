-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0028 D3 / issue #1275 — per-currency variants of the lending book's leaf GL accounts.
--
-- V19 seeded the five lending leaves in CZK only, on the assumption that "loans are single-currency".
-- They are single-currency PER LOAN, but the loan currency is client-supplied (Loan.principal: Money,
-- positivity-checked only — EUR is the canonical fixture currency), NOT fixed to CZK. ledger-service
-- rejects (422) a journal line whose currency does not match its GL account's currency_code, so the
-- first EUR/USD/GBP disbursement, repayment, interest, write-off or provisioning posting 422s against
-- the CZK-only leaves — the same failure V14 fixed for cash-clearing (per-currency 1101/1102/1103) and
-- the same class as the original #1275. LendingGlChart selects the leaf by loan currency; this seeds
-- the accounts it points at.
--
-- Convention matches V14 (cash clearing) and the chart's XX01 EUR / XX02 USD / XX03 GBP suffixes.
-- Types mirror V19: Loans/Interest Receivable + Loan Loss Allowance = ASSET (allowance is a contra-
-- asset carried as a negative-balance asset), Interest Income = INCOME, Loan Loss Expense = EXPENSE.
-- No new funding-clearing accounts: lending reuses the shared Customer Cash Clearing set
-- (…-000001 / 1101 / 1102 / 1103) transaction-service already posts against.

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    -- Loans Receivable
    ('a0000000-0000-0000-0000-000000001201', '1201', 'Loans Receivable (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001202', '1202', 'Loans Receivable (USD)', 'ASSET', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000001203', '1203', 'Loans Receivable (GBP)', 'ASSET', 'GBP', true, true),
    -- Interest Receivable
    ('a0000000-0000-0000-0000-000000001301', '1301', 'Interest Receivable (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001302', '1302', 'Interest Receivable (USD)', 'ASSET', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000001303', '1303', 'Interest Receivable (GBP)', 'ASSET', 'GBP', true, true),
    -- Loan Loss Allowance (contra-asset)
    ('a0000000-0000-0000-0000-000000001401', '1401', 'Loan Loss Allowance (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001402', '1402', 'Loan Loss Allowance (USD)', 'ASSET', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000001403', '1403', 'Loan Loss Allowance (GBP)', 'ASSET', 'GBP', true, true),
    -- Interest Income
    ('a0000000-0000-0000-0000-000000004101', '4101', 'Interest Income (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004102', '4102', 'Interest Income (USD)', 'INCOME', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000004103', '4103', 'Interest Income (GBP)', 'INCOME', 'GBP', true, true),
    -- Loan Loss Expense
    ('a0000000-0000-0000-0000-000000005101', '5101', 'Loan Loss Expense (EUR)', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005102', '5102', 'Loan Loss Expense (USD)', 'EXPENSE', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000005103', '5103', 'Loan Loss Expense (GBP)', 'EXPENSE', 'GBP', true, true);

-- Rollback: safe only before any journal_lines reference these accounts (the FK blocks the DELETE
-- otherwise) — i.e. before openbank-lending-service posts a real non-CZK entry in a live environment.
--   DELETE FROM gl_accounts WHERE code IN (
--       '1201','1202','1203','1301','1302','1303','1401','1402','1403',
--       '4101','4102','4103','5101','5102','5103');
