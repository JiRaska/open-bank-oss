-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Issue #13226: non-CZK product fees never reached the ledger. The chart had one fee-income account,
-- 4003 (V15), in CZK, and the ledger refuses a line whose currency differs from its GL account's, so
-- V32 deliberately left FEE_INCOME_ACCOUNT unmapped outside CZK and every EUR/USD/GBP fee's credit leg
-- answered 422 "no GL account is mapped".
--
-- Accounting decision: fee income is booked in the fee's ORIGINAL currency on a per-currency sibling of
-- 4003, the same shape the chart already uses for every multi-currency family (2100-2103 deposit
-- control, 4010-4013 interest expense, 1990-1993 FX position): one single-currency leaf per currency,
-- stable UUID whose last segment is the account code. Revaluing these balances into CZK is FX
-- revaluation (ADR-0025/0046) and out of scope here; nothing in this migration converts an amount.
--
-- 4004-4006 are free on main and are not claimed by any open migration (#13413's V34 uses 42xx/43xx).
--
-- Rollback (only while no journal_lines reference the new accounts -- the FK blocks it otherwise, and
-- once a non-CZK fee has posted, rolling back means reversing those journals first):
--   DELETE FROM accounting_class_gl_map
--    WHERE accounting_class = 'FEE_INCOME_ACCOUNT' AND role = 'FEE_INCOME' AND currency_code IN ('EUR','USD','GBP');
--   DELETE FROM gl_accounts WHERE code IN ('4004','4005','4006');

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000004004', '4004', 'Fee Income EUR', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004005', '4005', 'Fee Income USD', 'INCOME', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000004006', '4006', 'Fee Income GBP', 'INCOME', 'GBP', true, true);

-- Looked up by code, never by literal id, exactly as V32 does.
INSERT INTO accounting_class_gl_map (id, accounting_class, role, currency_code, gl_account_id)
SELECT gen_random_uuid(), 'FEE_INCOME_ACCOUNT', 'FEE_INCOME', m.currency_code, g.id
FROM (VALUES ('EUR', '4004'), ('USD', '4005'), ('GBP', '4006')) AS m (currency_code, gl_code)
JOIN gl_accounts g ON g.code = m.gl_code;

-- Every currency that has a deposit-control mapping for fees must now have a fee-income mapping too,
-- each on an account of its own currency. A hole here is the exact defect this migration closes, so it
-- fails the migration rather than shipping.
DO $$
BEGIN
    IF (SELECT count(*) FROM accounting_class_gl_map
        WHERE accounting_class = 'FEE_INCOME_ACCOUNT' AND role = 'FEE_INCOME') <> 4 THEN
        RAISE EXCEPTION 'FEE_INCOME_ACCOUNT is mapped in % of 4 currencies (CZK, EUR, USD, GBP)',
            (SELECT count(*) FROM accounting_class_gl_map
             WHERE accounting_class = 'FEE_INCOME_ACCOUNT' AND role = 'FEE_INCOME');
    END IF;
    IF EXISTS (
        SELECT 1 FROM accounting_class_gl_map m JOIN gl_accounts g ON g.id = m.gl_account_id
        WHERE g.currency_code <> m.currency_code
    ) THEN
        RAISE EXCEPTION 'accounting_class_gl_map has a row whose currency differs from its GL account''s';
    END IF;
END $$;
