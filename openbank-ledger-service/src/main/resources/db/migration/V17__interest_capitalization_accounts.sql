-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0033 §D — the GL accounts the credit-interest capitalization split posts against.
--
-- openbank-interest-service records a capitalization + a withholding-tax liability in its own
-- tables but posts NOTHING to the ledger, while WithholdingRemittanceSettlementConsumer already
-- moves real money to the finanční úřad. The bank therefore remits withholding tax on interest it
-- never credited to anyone, with no GL record of either the expense or the customer liability.
-- The split that closes this is:
--
--     DEBIT  Interest Expense           (gross)
--     CREDIT Customer Deposit Control   (net, subAccountId = customer account)
--     CREDIT Withholding Tax Payable    (tax, omitted when nothing was withheld)
--
-- Two of those three legs have no usable account today.
--
-- 1. Withholding Tax Payable did not exist at all. Seeded below as '2200' (LIABILITY, CZK) — CZK
--    only, because ADR-0033 §E withholds only CZK-denominated interest (foreign interest records
--    treatment = DEFERRED_FX and tax = 0), so a per-currency family would seed three accounts that
--    can never be posted against.
--
-- 2. Interest Expense DOES exist as '4000' (V1__init_ledger.sql) but is unusable twice over:
--
--    a. V1 seeded it with gen_random_uuid(), so its id differs per deployment and no consuming
--       service can hardcode a working reference — the exact defect issue #468 hit with '4001 Fee
--       Income', which V15__stable_fee_income_account.sql fixed by seeding a NEW stable-UUID row
--       ('4003') rather than mutating V1's. Every real posting against the random id 422s with
--       "GL account not found" (see BillingLedgerConfig.Gl.feeIncome's note). Same remedy here:
--       V1's '4000' row is left untouched (an applied migration is never edited, and UPDATEing a
--       primary key risks orphaning journal_lines), and the unique constraint on `code` means the
--       replacement needs a free code — hence the '401x' family below rather than '4000'.
--
--    b. It is declared CZK. LedgerService.postJournal rejects a line whose baseCurrencyCode does
--       not match its GL account's declared currency (422), so a CZK-only expense account cannot
--       carry the DEBIT leg of a EUR/USD/GBP interest capitalization — the entry would fail whole,
--       taking the (untaxed, but still real) foreign-currency credit with it. This is exactly the
--       bug V14__cash_clearing_accounts_per_currency.sql fixed for cash-clearing ("confirmed live,
--       a EUR CREDIT transaction failed where CZK succeeded"). Interest expense is the same shape,
--       so it gets the same per-currency treatment.
--
-- Numbering follows the V5/V14 convention (base + XX01/XX02/XX03 = EUR/USD/GBP) and the stable-UUID
-- chart convention (last UUID segment = account code). The paired deposit-control accounts these
-- entries credit already exist and are already stable: '2100' CZK (V3, id …0002) and '2101'/'2102'/
-- '2103' EUR/USD/GBP (V5).

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000002200', '2200', 'Withholding Tax Payable', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004010', '4010', 'Interest Expense CZK', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004011', '4011', 'Interest Expense EUR', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004012', '4012', 'Interest Expense USD', 'EXPENSE', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000004013', '4013', 'Interest Expense GBP', 'EXPENSE', 'GBP', true, true);

-- Rollback:
--   DELETE FROM gl_accounts WHERE id IN (
--       'a0000000-0000-0000-0000-000000002200',
--       'a0000000-0000-0000-0000-000000004010',
--       'a0000000-0000-0000-0000-000000004011',
--       'a0000000-0000-0000-0000-000000004012',
--       'a0000000-0000-0000-0000-000000004013');
-- Safe to roll back only before any journal_lines reference these accounts (the FK would block the
-- DELETE otherwise) — i.e. before openbank-interest-service capitalizes in a live environment.
