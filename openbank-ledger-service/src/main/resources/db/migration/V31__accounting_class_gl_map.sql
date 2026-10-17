-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0341 D3: the ledger owns the chart of accounts AND the accountingClass -> GL account map.
-- Product services post with (accountingClass, role, currency) and the ledger resolves the account,
-- so no product service carries a literal GL code. The map is data, changed exactly the way the chart
-- is: by a reviewed migration (money-path, two approvals). There is deliberately no runtime write
-- path -- a runtime editor would be a second, weaker way to re-point a product's postings.
-- Rollback: DROP TABLE accounting_class_gl_map. Nothing else references it; posters that already
-- send glAccountId are unaffected, posters that send accountingClass fall back to their old literal
-- codes only if their previous release is redeployed.

CREATE TABLE accounting_class_gl_map (
    id                UUID         PRIMARY KEY,
    accounting_class  VARCHAR(64)  NOT NULL,
    role              VARCHAR(32)  NOT NULL,
    currency_code     CHAR(3)      NOT NULL,
    gl_account_id     UUID         NOT NULL REFERENCES gl_accounts (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_accounting_class_gl_map UNIQUE (accounting_class, role, currency_code),
    CONSTRAINT ck_accounting_class_gl_map_role CHECK (role IN (
        'PRINCIPAL', 'ACCRUED_INTEREST', 'INTEREST_INCOME', 'INTEREST_EXPENSE', 'FEE_INCOME', 'FEE_SETTLEMENT'
    ))
);

-- The GL account is looked up BY CODE, never by a literal id: V29 may have re-keyed 1001, and a
-- literal id there would point at a row that does not exist.
INSERT INTO accounting_class_gl_map (id, accounting_class, role, currency_code, gl_account_id)
SELECT gen_random_uuid(), m.accounting_class, m.role, m.currency_code, g.id
FROM (VALUES
    -- treasury (ADR-0315): the same accounts TreasuryChart used to name by code
    ('NOSTRO_ACCOUNT',        'PRINCIPAL',        'CZK', '1001'),
    ('NOSTRO_ACCOUNT',        'PRINCIPAL',        'EUR', '1002'),
    ('INTERBANK_PLACEMENT',   'PRINCIPAL',        'CZK', '1500'),
    ('INTERBANK_PLACEMENT',   'PRINCIPAL',        'EUR', '1501'),
    ('INTERBANK_PLACEMENT',   'ACCRUED_INTEREST', 'CZK', '1520'),
    ('INTERBANK_PLACEMENT',   'ACCRUED_INTEREST', 'EUR', '1521'),
    ('INTERBANK_PLACEMENT',   'INTEREST_INCOME',  'CZK', '4200'),
    ('INTERBANK_PLACEMENT',   'INTEREST_INCOME',  'EUR', '4201'),
    ('INTERBANK_BORROWING',   'PRINCIPAL',        'CZK', '2300'),
    ('INTERBANK_BORROWING',   'PRINCIPAL',        'EUR', '2301'),
    ('INTERBANK_BORROWING',   'ACCRUED_INTEREST', 'CZK', '2310'),
    ('INTERBANK_BORROWING',   'ACCRUED_INTEREST', 'EUR', '2311'),
    ('INTERBANK_BORROWING',   'INTEREST_EXPENSE', 'CZK', '5200'),
    ('INTERBANK_BORROWING',   'INTEREST_EXPENSE', 'EUR', '5201'),
    ('CNB_DEPOSIT_FACILITY',  'PRINCIPAL',        'CZK', '1510'),
    ('CNB_DEPOSIT_FACILITY',  'ACCRUED_INTEREST', 'CZK', '1520'),
    ('CNB_DEPOSIT_FACILITY',  'INTEREST_INCOME',  'CZK', '4200'),
    ('CNB_LOMBARD_BORROWING', 'PRINCIPAL',        'CZK', '2320'),
    ('CNB_LOMBARD_BORROWING', 'ACCRUED_INTEREST', 'CZK', '2310'),
    ('CNB_LOMBARD_BORROWING', 'INTEREST_EXPENSE', 'CZK', '5200'),
    ('FX_SPOT_POSITION',      'PRINCIPAL',        'CZK', '1990'),
    ('FX_SPOT_POSITION',      'PRINCIPAL',        'EUR', '1991'),
    -- fee billing (ADR-0143). Billing used to debit the fixed account a0..0002 (2100) and credit a0..4003
    -- (4003) for EVERY currency, and ADR-0341 must not move a single fee posting. So the fee debit
    -- leg has its own role, FEE_SETTLEMENT, which maps to 2100 in every currency: the per-currency
    -- control accounts 2101..2103 stay reachable through PRINCIPAL for posters that genuinely
    -- post EUR/USD/GBP deposits there, and nobody's fee silently changes account. Whether a EUR fee
    -- SHOULD debit the EUR control account is an accounting decision for a later reviewed migration.
    ('CUSTOMER_DEPOSIT_DEMAND',  'PRINCIPAL',      'CZK', '2100'),
    ('CUSTOMER_DEPOSIT_DEMAND',  'PRINCIPAL',      'EUR', '2101'),
    ('CUSTOMER_DEPOSIT_DEMAND',  'PRINCIPAL',      'USD', '2102'),
    ('CUSTOMER_DEPOSIT_DEMAND',  'PRINCIPAL',      'GBP', '2103'),
    ('CUSTOMER_DEPOSIT_SAVINGS', 'PRINCIPAL',      'CZK', '2100'),
    ('CUSTOMER_DEPOSIT_SAVINGS', 'PRINCIPAL',      'EUR', '2101'),
    ('CUSTOMER_DEPOSIT_SAVINGS', 'PRINCIPAL',      'USD', '2102'),
    ('CUSTOMER_DEPOSIT_SAVINGS', 'PRINCIPAL',      'GBP', '2103'),
    ('CUSTOMER_DEPOSIT_TERM',    'PRINCIPAL',      'CZK', '2100'),
    ('CUSTOMER_DEPOSIT_TERM',    'PRINCIPAL',      'EUR', '2101'),
    ('CUSTOMER_DEPOSIT_TERM',    'PRINCIPAL',      'USD', '2102'),
    ('CUSTOMER_DEPOSIT_TERM',    'PRINCIPAL',      'GBP', '2103'),
    ('CUSTOMER_DEPOSIT_DEMAND',    'FEE_SETTLEMENT', 'CZK', '2100'),
    ('CUSTOMER_DEPOSIT_DEMAND',    'FEE_SETTLEMENT', 'EUR', '2100'),
    ('CUSTOMER_DEPOSIT_DEMAND',    'FEE_SETTLEMENT', 'USD', '2100'),
    ('CUSTOMER_DEPOSIT_DEMAND',    'FEE_SETTLEMENT', 'GBP', '2100'),
    ('CUSTOMER_DEPOSIT_SAVINGS',   'FEE_SETTLEMENT', 'CZK', '2100'),
    ('CUSTOMER_DEPOSIT_SAVINGS',   'FEE_SETTLEMENT', 'EUR', '2100'),
    ('CUSTOMER_DEPOSIT_SAVINGS',   'FEE_SETTLEMENT', 'USD', '2100'),
    ('CUSTOMER_DEPOSIT_SAVINGS',   'FEE_SETTLEMENT', 'GBP', '2100'),
    ('CUSTOMER_DEPOSIT_TERM',      'FEE_SETTLEMENT', 'CZK', '2100'),
    ('CUSTOMER_DEPOSIT_TERM',      'FEE_SETTLEMENT', 'EUR', '2100'),
    ('CUSTOMER_DEPOSIT_TERM',      'FEE_SETTLEMENT', 'USD', '2100'),
    ('CUSTOMER_DEPOSIT_TERM',      'FEE_SETTLEMENT', 'GBP', '2100'),
    ('FEE_INCOME_ACCOUNT',      'FEE_INCOME',     'CZK', '4003'),
    ('FEE_INCOME_ACCOUNT',      'FEE_INCOME',     'EUR', '4003'),
    ('FEE_INCOME_ACCOUNT',      'FEE_INCOME',     'USD', '4003'),
    ('FEE_INCOME_ACCOUNT',      'FEE_INCOME',     'GBP', '4003')
) AS m (accounting_class, role, currency_code, gl_code)
JOIN gl_accounts g ON g.code = m.gl_code;

-- A row the JOIN dropped means a GL code is missing: fail the migration rather than ship a map with
-- a silent hole that only shows up as a 422 on the first deal in that currency.
DO $$
BEGIN
    IF (SELECT count(*) FROM accounting_class_gl_map) <> 50 THEN
        RAISE EXCEPTION 'accounting_class_gl_map seeded % of 50 rows: a GL code it names does not exist',
            (SELECT count(*) FROM accounting_class_gl_map);
    END IF;
END $$;
