-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315 D5 (#10618): GL accounts for openbank-treasury-service's money-market deals. The
-- treasury service posts ONLY through POST /api/v1/journals and addresses accounts by these fixed
-- ids (a0000000-0000-0000-0000-00000000<code>), the convention of V14/V20/V26. Per currency from the
-- start: postJournal rejects (422) a line whose currency differs from its account's currency_code.
--
-- | code | name                                         | type      | ccy |
-- | 1002 | Nostro Accounts (EUR)                        | ASSET     | EUR |
-- | 1500 | MM Placements with Banks                     | ASSET     | CZK |
-- | 1501 | MM Placements with Banks (EUR)               | ASSET     | EUR |
-- | 1510 | Deposit Facility at CNB                      | ASSET     | CZK |  <- HQLA L1 candidate (risk-engine)
-- | 1520 | Accrued Interest Receivable - MM             | ASSET     | CZK |  seeded for daily accrual;
-- | 1521 | Accrued Interest Receivable - MM (EUR)       | ASSET     | EUR |  the MVP recognises interest
-- | 2300 | MM Borrowings from Banks                     | LIABILITY | CZK |  at maturity and does not
-- | 2301 | MM Borrowings from Banks (EUR)               | LIABILITY | EUR |  post to 1520/1521/2310/2311
-- | 2310 | Accrued Interest Payable - MM                | LIABILITY | CZK |
-- | 2311 | Accrued Interest Payable - MM (EUR)          | LIABILITY | EUR |
-- | 4200 | MM Interest Income                           | INCOME    | CZK |
-- | 4201 | MM Interest Income (EUR)                     | INCOME    | EUR |
-- | 5200 | MM Interest Expense                          | EXPENSE   | CZK |
-- | 5201 | MM Interest Expense (EUR)                    | EXPENSE   | EUR |
--
-- CZK nostro stays 1001. V1 seeded it with gen_random_uuid(), so its id differs per environment
-- and no service can address it. It is re-keyed to the fixed id below ONLY while no journal line
-- references it (nothing in the repo posts to 1001 today; the FK would otherwise make the UPDATE
-- fail and stop the service booting). If it IS referenced, it is left alone and a NOTICE is
-- raised — treasury's CZK settlement then fails LOUDLY (422 unknown GL account) instead of posting
-- to the wrong account; the fix in that environment is an explicit, reviewed re-key.
DO $$
DECLARE
    nostro_id UUID;
BEGIN
    SELECT id INTO nostro_id FROM gl_accounts WHERE code = '1001';
    IF nostro_id IS NULL OR nostro_id = 'a0000000-0000-0000-0000-000000001001'::uuid THEN
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM journal_lines WHERE gl_account_id = nostro_id)
       OR EXISTS (SELECT 1 FROM gl_accounts WHERE parent_id = nostro_id)
       -- no FK, but a frozen period's trial balance names the account by id (V23)
       OR EXISTS (SELECT 1 FROM ledger_closed_period_trial_balance_line WHERE gl_account_id = nostro_id) THEN
        RAISE NOTICE 'gl_accounts 1001 (%) is referenced; not re-keyed — treasury CZK postings will 422 until it is', nostro_id;
        RETURN;
    END IF;
    UPDATE gl_accounts SET id = 'a0000000-0000-0000-0000-000000001001'::uuid WHERE id = nostro_id;
END $$;

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000001002', '1002', 'Nostro Accounts (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001500', '1500', 'MM Placements with Banks', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001501', '1501', 'MM Placements with Banks (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001510', '1510', 'Deposit Facility at CNB', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001520', '1520', 'Accrued Interest Receivable - MM', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001521', '1521', 'Accrued Interest Receivable - MM (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002300', '2300', 'MM Borrowings from Banks', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002301', '2301', 'MM Borrowings from Banks (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002310', '2310', 'Accrued Interest Payable - MM', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002311', '2311', 'Accrued Interest Payable - MM (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004200', '4200', 'MM Interest Income', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004201', '4201', 'MM Interest Income (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005200', '5200', 'MM Interest Expense', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005201', '5201', 'MM Interest Expense (EUR)', 'EXPENSE', 'EUR', true, true);

-- Rollback (safe only while no journal_line references these accounts — check first):
--   DELETE FROM gl_accounts WHERE code IN ('1002','1500','1501','1510','1520','1521','2300','2301',
--       '2310','2311','4200','4201','5200','5201');
--   The 1001 re-key is harmless to keep; to undo it, UPDATE gl_accounts SET id = gen_random_uuid()
--   WHERE code = '1001' (again only while unreferenced).
