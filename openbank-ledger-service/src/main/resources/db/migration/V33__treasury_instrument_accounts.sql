-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D7 (#10618): the GL accounts the treasury posting-rule matrix posts to for the eleven
-- leg-described products (securities, loans, derivatives, repos, cash), per currency, by the fixed-id
-- convention of V29 (a0000000-0000-0000-0000-00000000<code>). The treasury service posts ONLY
-- through POST /api/v1/journals; postJournal rejects (422) a line whose currency differs from its
-- account's currency_code, so every account exists once per currency (EUR = CZK code + 1).
--
-- Securities are split by IFRS 9 measurement category (AC / FVOCI / FVTPL) of the book that holds
-- them, with one unamortised premium/discount account. 90xx/91xx are MEMO pairs for the
-- off-balance-sheet commitments of forward-start deals and for repo collateral: the chart has no
-- off-balance type, so each memo asset has a contra liability and a memo journal always balances
-- within the pair — it never touches a real balance-sheet account.
--
-- New accounts only; nothing existing changes, and no code collides with V1..V32.
--
-- ADR-0341 D3: treasury names a journal leg by (accountingClass, role, currency), never a GL code, so
-- this migration also maps the matrix's classes onto the new accounts in accounting_class_gl_map
-- (V32), seeded BY CODE exactly as V32 does. The role vocabulary grows additively by the instrument
-- roles the matrix needs (premium/discount, fair value, revaluation reserve, realised and
-- unrealised result, memo and memo contra); V32's five roles and rows are untouched.
-- Rollback: DELETE the V33 map rows, restore the V32 role CHECK, then delete the accounts while no
-- journal line references them.

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000001600', '1600', 'Treasury Securities - Amortised Cost', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001601', '1601', 'Treasury Securities - Amortised Cost (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001610', '1610', 'Treasury Securities - FVOCI', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001611', '1611', 'Treasury Securities - FVOCI (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001620', '1620', 'Treasury Securities - FVTPL', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001621', '1621', 'Treasury Securities - FVTPL (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001630', '1630', 'Treasury Securities - Unamortised Premium/Discount', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001631', '1631', 'Treasury Securities - Unamortised Premium/Discount (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001640', '1640', 'Accrued Interest Receivable - Securities', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001641', '1641', 'Accrued Interest Receivable - Securities (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001650', '1650', 'Accrued Interest Receivable - Treasury Loans', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001651', '1651', 'Accrued Interest Receivable - Treasury Loans (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001660', '1660', 'Treasury Loans Granted', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001661', '1661', 'Treasury Loans Granted (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001670', '1670', 'Accrued Interest Receivable - Derivatives', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001671', '1671', 'Accrued Interest Receivable - Derivatives (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001680', '1680', 'Reverse Repo Receivable', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001681', '1681', 'Reverse Repo Receivable (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001685', '1685', 'Accrued Interest Receivable - Reverse Repo', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001686', '1686', 'Accrued Interest Receivable - Reverse Repo (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001690', '1690', 'Treasury Cash in Transit', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001691', '1691', 'Treasury Cash in Transit (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001700', '1700', 'Derivative Assets - Fair Value', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001701', '1701', 'Derivative Assets - Fair Value (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002400', '2400', 'Derivative Liabilities - Fair Value', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002401', '2401', 'Derivative Liabilities - Fair Value (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002410', '2410', 'Accrued Interest Payable - Derivatives', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002411', '2411', 'Accrued Interest Payable - Derivatives (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002420', '2420', 'Accrued Interest Payable - Repo', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002421', '2421', 'Accrued Interest Payable - Repo (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002430', '2430', 'Repo Payable', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002431', '2431', 'Repo Payable (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000003100', '3100', 'FVOCI Revaluation Reserve', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000003101', '3101', 'FVOCI Revaluation Reserve (EUR)', 'EQUITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004210', '4210', 'Interest Income - Securities', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004211', '4211', 'Interest Income - Securities (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004220', '4220', 'Interest Income - Treasury Loans', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004221', '4221', 'Interest Income - Treasury Loans (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004230', '4230', 'Interest Income - Derivatives', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004231', '4231', 'Interest Income - Derivatives (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004240', '4240', 'Interest Income - Reverse Repo', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004241', '4241', 'Interest Income - Reverse Repo (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004300', '4300', 'Realised Gains - Treasury', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004301', '4301', 'Realised Gains - Treasury (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000004310', '4310', 'Unrealised Gains - Treasury', 'INCOME', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000004311', '4311', 'Unrealised Gains - Treasury (EUR)', 'INCOME', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005230', '5230', 'Interest Expense - Derivatives', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005231', '5231', 'Interest Expense - Derivatives (EUR)', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005240', '5240', 'Interest Expense - Repo', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005241', '5241', 'Interest Expense - Repo (EUR)', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005300', '5300', 'Realised Losses - Treasury', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005301', '5301', 'Realised Losses - Treasury (EUR)', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005310', '5310', 'Unrealised Losses - Treasury', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005311', '5311', 'Unrealised Losses - Treasury (EUR)', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000009000', '9000', 'Forward Commitments - Memo', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000009001', '9001', 'Forward Commitments - Memo (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000009100', '9100', 'Forward Commitments - Memo Contra', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000009101', '9101', 'Forward Commitments - Memo Contra (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000009010', '9010', 'Collateral Received - Memo', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000009011', '9011', 'Collateral Received - Memo (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000009110', '9110', 'Collateral Received - Memo Contra', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000009111', '9111', 'Collateral Received - Memo Contra (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000009020', '9020', 'Collateral Pledged - Memo', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000009021', '9021', 'Collateral Pledged - Memo (EUR)', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000009120', '9120', 'Collateral Pledged - Memo Contra', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000009121', '9121', 'Collateral Pledged - Memo Contra (EUR)', 'LIABILITY', 'EUR', true, true);

ALTER TABLE accounting_class_gl_map DROP CONSTRAINT ck_accounting_class_gl_map_role;
ALTER TABLE accounting_class_gl_map ADD CONSTRAINT ck_accounting_class_gl_map_role CHECK (role IN (
    'PRINCIPAL', 'ACCRUED_INTEREST', 'INTEREST_INCOME', 'INTEREST_EXPENSE', 'FEE_INCOME',
    'PREMIUM_DISCOUNT', 'FAIR_VALUE', 'REVALUATION_RESERVE', 'REALISED_GAIN', 'REALISED_LOSS',
    'UNREALISED_GAIN', 'UNREALISED_LOSS', 'MEMO', 'MEMO_CONTRA'
));

-- One debt-security class per IFRS 9 category; the three share the premium/discount, accrued and
-- income accounts and differ in the principal account. The derivative classes split asset from
-- liability and receive leg from pay leg, as V32 splits placement from borrowing.
INSERT INTO accounting_class_gl_map (id, accounting_class, role, currency_code, gl_account_id)
SELECT gen_random_uuid(), m.accounting_class, m.role, m.currency_code, g.id
FROM (VALUES
    ('CASH_IN_TRANSIT', 'PRINCIPAL', 'CZK', '1690'),
    ('CASH_IN_TRANSIT', 'PRINCIPAL', 'EUR', '1691'),
    ('TREASURY_LOAN', 'PRINCIPAL', 'CZK', '1660'),
    ('TREASURY_LOAN', 'PRINCIPAL', 'EUR', '1661'),
    ('TREASURY_LOAN', 'ACCRUED_INTEREST', 'CZK', '1650'),
    ('TREASURY_LOAN', 'ACCRUED_INTEREST', 'EUR', '1651'),
    ('TREASURY_LOAN', 'INTEREST_INCOME', 'CZK', '4220'),
    ('TREASURY_LOAN', 'INTEREST_INCOME', 'EUR', '4221'),
    ('DEBT_SECURITY_AMORTISED_COST', 'PRINCIPAL', 'CZK', '1600'),
    ('DEBT_SECURITY_AMORTISED_COST', 'PRINCIPAL', 'EUR', '1601'),
    ('DEBT_SECURITY_AMORTISED_COST', 'PREMIUM_DISCOUNT', 'CZK', '1630'),
    ('DEBT_SECURITY_AMORTISED_COST', 'PREMIUM_DISCOUNT', 'EUR', '1631'),
    ('DEBT_SECURITY_AMORTISED_COST', 'ACCRUED_INTEREST', 'CZK', '1640'),
    ('DEBT_SECURITY_AMORTISED_COST', 'ACCRUED_INTEREST', 'EUR', '1641'),
    ('DEBT_SECURITY_AMORTISED_COST', 'INTEREST_INCOME', 'CZK', '4210'),
    ('DEBT_SECURITY_AMORTISED_COST', 'INTEREST_INCOME', 'EUR', '4211'),
    ('DEBT_SECURITY_FVOCI', 'PRINCIPAL', 'CZK', '1610'),
    ('DEBT_SECURITY_FVOCI', 'PRINCIPAL', 'EUR', '1611'),
    ('DEBT_SECURITY_FVOCI', 'PREMIUM_DISCOUNT', 'CZK', '1630'),
    ('DEBT_SECURITY_FVOCI', 'PREMIUM_DISCOUNT', 'EUR', '1631'),
    ('DEBT_SECURITY_FVOCI', 'ACCRUED_INTEREST', 'CZK', '1640'),
    ('DEBT_SECURITY_FVOCI', 'ACCRUED_INTEREST', 'EUR', '1641'),
    ('DEBT_SECURITY_FVOCI', 'INTEREST_INCOME', 'CZK', '4210'),
    ('DEBT_SECURITY_FVOCI', 'INTEREST_INCOME', 'EUR', '4211'),
    ('DEBT_SECURITY_FVTPL', 'PRINCIPAL', 'CZK', '1620'),
    ('DEBT_SECURITY_FVTPL', 'PRINCIPAL', 'EUR', '1621'),
    ('DEBT_SECURITY_FVTPL', 'PREMIUM_DISCOUNT', 'CZK', '1630'),
    ('DEBT_SECURITY_FVTPL', 'PREMIUM_DISCOUNT', 'EUR', '1631'),
    ('DEBT_SECURITY_FVTPL', 'ACCRUED_INTEREST', 'CZK', '1640'),
    ('DEBT_SECURITY_FVTPL', 'ACCRUED_INTEREST', 'EUR', '1641'),
    ('DEBT_SECURITY_FVTPL', 'INTEREST_INCOME', 'CZK', '4210'),
    ('DEBT_SECURITY_FVTPL', 'INTEREST_INCOME', 'EUR', '4211'),
    ('DEBT_SECURITY_FVOCI', 'REVALUATION_RESERVE', 'CZK', '3100'),
    ('DEBT_SECURITY_FVOCI', 'REVALUATION_RESERVE', 'EUR', '3101'),
    ('REPO_BORROWING', 'PRINCIPAL', 'CZK', '2430'),
    ('REPO_BORROWING', 'PRINCIPAL', 'EUR', '2431'),
    ('REPO_BORROWING', 'ACCRUED_INTEREST', 'CZK', '2420'),
    ('REPO_BORROWING', 'ACCRUED_INTEREST', 'EUR', '2421'),
    ('REPO_BORROWING', 'INTEREST_EXPENSE', 'CZK', '5240'),
    ('REPO_BORROWING', 'INTEREST_EXPENSE', 'EUR', '5241'),
    ('REVERSE_REPO_LENDING', 'PRINCIPAL', 'CZK', '1680'),
    ('REVERSE_REPO_LENDING', 'PRINCIPAL', 'EUR', '1681'),
    ('REVERSE_REPO_LENDING', 'ACCRUED_INTEREST', 'CZK', '1685'),
    ('REVERSE_REPO_LENDING', 'ACCRUED_INTEREST', 'EUR', '1686'),
    ('REVERSE_REPO_LENDING', 'INTEREST_INCOME', 'CZK', '4240'),
    ('REVERSE_REPO_LENDING', 'INTEREST_INCOME', 'EUR', '4241'),
    ('DERIVATIVE_ASSET', 'FAIR_VALUE', 'CZK', '1700'),
    ('DERIVATIVE_ASSET', 'FAIR_VALUE', 'EUR', '1701'),
    ('DERIVATIVE_LIABILITY', 'FAIR_VALUE', 'CZK', '2400'),
    ('DERIVATIVE_LIABILITY', 'FAIR_VALUE', 'EUR', '2401'),
    ('DERIVATIVE_RECEIVE_LEG', 'ACCRUED_INTEREST', 'CZK', '1670'),
    ('DERIVATIVE_RECEIVE_LEG', 'ACCRUED_INTEREST', 'EUR', '1671'),
    ('DERIVATIVE_RECEIVE_LEG', 'INTEREST_INCOME', 'CZK', '4230'),
    ('DERIVATIVE_RECEIVE_LEG', 'INTEREST_INCOME', 'EUR', '4231'),
    ('DERIVATIVE_PAY_LEG', 'ACCRUED_INTEREST', 'CZK', '2410'),
    ('DERIVATIVE_PAY_LEG', 'ACCRUED_INTEREST', 'EUR', '2411'),
    ('DERIVATIVE_PAY_LEG', 'INTEREST_EXPENSE', 'CZK', '5230'),
    ('DERIVATIVE_PAY_LEG', 'INTEREST_EXPENSE', 'EUR', '5231'),
    ('TREASURY_TRADING_RESULT', 'REALISED_GAIN', 'CZK', '4300'),
    ('TREASURY_TRADING_RESULT', 'REALISED_GAIN', 'EUR', '4301'),
    ('TREASURY_TRADING_RESULT', 'REALISED_LOSS', 'CZK', '5300'),
    ('TREASURY_TRADING_RESULT', 'REALISED_LOSS', 'EUR', '5301'),
    ('TREASURY_TRADING_RESULT', 'UNREALISED_GAIN', 'CZK', '4310'),
    ('TREASURY_TRADING_RESULT', 'UNREALISED_GAIN', 'EUR', '4311'),
    ('TREASURY_TRADING_RESULT', 'UNREALISED_LOSS', 'CZK', '5310'),
    ('TREASURY_TRADING_RESULT', 'UNREALISED_LOSS', 'EUR', '5311'),
    ('FORWARD_COMMITMENT', 'MEMO', 'CZK', '9000'),
    ('FORWARD_COMMITMENT', 'MEMO', 'EUR', '9001'),
    ('FORWARD_COMMITMENT', 'MEMO_CONTRA', 'CZK', '9100'),
    ('FORWARD_COMMITMENT', 'MEMO_CONTRA', 'EUR', '9101'),
    ('COLLATERAL_RECEIVED', 'MEMO', 'CZK', '9010'),
    ('COLLATERAL_RECEIVED', 'MEMO', 'EUR', '9011'),
    ('COLLATERAL_RECEIVED', 'MEMO_CONTRA', 'CZK', '9110'),
    ('COLLATERAL_RECEIVED', 'MEMO_CONTRA', 'EUR', '9111'),
    ('COLLATERAL_PLEDGED', 'MEMO', 'CZK', '9020'),
    ('COLLATERAL_PLEDGED', 'MEMO', 'EUR', '9021'),
    ('COLLATERAL_PLEDGED', 'MEMO_CONTRA', 'CZK', '9120'),
    ('COLLATERAL_PLEDGED', 'MEMO_CONTRA', 'EUR', '9121')
) AS m (accounting_class, role, currency_code, gl_code)
JOIN gl_accounts g ON g.code = m.gl_code;

-- A row the JOIN dropped means a GL code is missing: fail rather than ship a silent hole (V32 idiom).
DO $$
BEGIN
    IF (SELECT count(*) FROM accounting_class_gl_map) <> 35 + 78 THEN
        RAISE EXCEPTION 'accounting_class_gl_map holds % of 113 rows after V33: a GL code it names does not exist',
            (SELECT count(*) FROM accounting_class_gl_map);
    END IF;
    IF EXISTS (
        SELECT 1 FROM accounting_class_gl_map m JOIN gl_accounts g ON g.id = m.gl_account_id
        WHERE g.currency_code <> m.currency_code
    ) THEN
        RAISE EXCEPTION 'accounting_class_gl_map has a row whose currency differs from its GL account''s';
    END IF;
END $$;
