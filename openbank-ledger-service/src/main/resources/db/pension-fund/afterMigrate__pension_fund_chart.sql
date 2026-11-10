-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Engineering draft, ADR-0365. Select this callback location ONLY on a segregated fund ledger.
-- Codes are repository codes, not an official statutory chart. No opening balances are invented.
-- Rollback retains accounts/mappings and all financial history; disable the producer instead.
INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    (gen_random_uuid(), 'PF1110', 'Fund custody cash', 'ASSET', 'CZK', true, true),
    (gen_random_uuid(), 'PF1500', 'Fund custody investments', 'ASSET', 'CZK', true, true),
    (gen_random_uuid(), 'PF2110', 'Management fee payable to company', 'LIABILITY', 'CZK', true, true),
    (gen_random_uuid(), 'PF2210', 'Unallocated participant money', 'LIABILITY', 'CZK', true, true),
    (gen_random_uuid(), 'PF2220', 'Participant payout payable', 'LIABILITY', 'CZK', true, true),
    (gen_random_uuid(), 'PF3010', 'Participant fund capital', 'EQUITY', 'CZK', true, true),
    (gen_random_uuid(), 'PF5110', 'Management fee expense', 'EXPENSE', 'CZK', true, true),
    (gen_random_uuid(), 'PF6210', 'Fund valuation result', 'INCOME', 'CZK', true, true)
ON CONFLICT (code) DO NOTHING;
UPDATE gl_accounts SET is_enabled = false WHERE code NOT LIKE 'PF%' AND is_enabled;
INSERT INTO accounting_class_gl_map (id, accounting_class, role, currency_code, gl_account_id)
SELECT gen_random_uuid(), m.accounting_class, 'PRINCIPAL', 'CZK', g.id
FROM (VALUES ('PENSION_MANAGEMENT_FEE_EXPENSE', 'PF5110'),
             ('PENSION_MANAGEMENT_FEE_PAYABLE', 'PF2110')) m(accounting_class, code)
JOIN gl_accounts g ON g.code = m.code
ON CONFLICT (accounting_class, role, currency_code) DO NOTHING;
