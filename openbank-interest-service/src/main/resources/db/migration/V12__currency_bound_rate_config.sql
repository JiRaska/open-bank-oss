-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Issue #1265 — a mixed-currency ACCRUING set wedges capitalize() forever, with no operator exit.
-- Root cause: currency was bound to neither the rate config nor the accrual key, so two dates under
-- one (account, product) could carry different currencies (the reachable path: accrueAll reads each
-- account's booked-balance currency, and interest_accruals.currency defaulted to 'EUR' while the
-- seeded product is CZK — so any accrual missing an explicit currency landed EUR against a CZK
-- product). capitalize() then reads ALL ACCRUING rows for the (account, product) and correctly
-- refuses to sum across currencies, but there is no way to unwedge the set.
--
-- Two structural fixes, together making the mixed set unreachable:
--   1. Bind currency to the rate config. Rates ARE currency-specific (a CZK savings rate is not an
--      EUR one), so resolution becomes (account/product, currency)-specific and accrue fails closed
--      when no rate exists for the accrual currency — an account can only accrue in a currency it
--      has a rate for. Backfill the seeded row(s) to CZK (the V9 product is literally "CZK Savings";
--      the bank is CZK-domestic and withholding is CZK-only).
--   2. Add currency to the accrual UNIQUE key so two same-date rows in different currencies can never
--      collapse into one capitalize set (defence in depth behind (1)).
-- Also drops the 'EUR' column default that was the original sin — the app now always sets currency
-- explicitly (accrue copies it from the AccrualRequest, which the scheduler fills from the account's
-- booked-balance currency), so a DB INSERT that omits it fails loudly rather than silently landing
-- an accrual in EUR against a CZK product.

-- 1. Currency on the rate config (backfill CZK for existing seeded rows, e.g. V9's CZK Savings rate).
ALTER TABLE interest_rate_configs
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'CZK';
-- Drop the backfill default: new configs must state their currency explicitly (the app always does).
ALTER TABLE interest_rate_configs
    ALTER COLUMN currency DROP DEFAULT;

-- 2. Currency in the accrual UNIQUE key; drop the 'EUR' default so currency is always explicit.
ALTER TABLE interest_accruals
    DROP CONSTRAINT interest_accruals_account_id_accrual_date_product_id_key;
ALTER TABLE interest_accruals
    ADD CONSTRAINT interest_accruals_account_date_product_currency_key
        UNIQUE (account_id, accrual_date, product_id, currency);
ALTER TABLE interest_accruals
    ALTER COLUMN currency DROP DEFAULT;

-- Rollback:
--   ALTER TABLE interest_accruals DROP CONSTRAINT interest_accruals_account_date_product_currency_key;
--   ALTER TABLE interest_accruals ADD CONSTRAINT interest_accruals_account_id_accrual_date_product_id_key
--       UNIQUE (account_id, accrual_date, product_id);
--   ALTER TABLE interest_accruals ALTER COLUMN currency SET DEFAULT 'EUR';
--   ALTER TABLE interest_rate_configs DROP COLUMN currency;
-- Safe only before any accrual relies on the per-currency key (i.e. before two currencies coexist
-- for one (account, product, date)).
