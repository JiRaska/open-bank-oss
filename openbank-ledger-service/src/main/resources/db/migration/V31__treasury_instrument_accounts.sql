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
-- New accounts only; nothing existing changes, and no code collides with V1..V30.

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
