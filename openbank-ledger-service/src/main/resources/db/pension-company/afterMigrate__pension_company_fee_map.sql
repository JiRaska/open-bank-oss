-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Select alongside the EXISTING per-instance company chart callback (ADR-0337).
-- Does not create company accounts in the bank; callbacks are outside db/migration.
-- Rollback retains mappings, receipts and journals; disable delivery instead.
INSERT INTO accounting_class_gl_map (id, accounting_class, role, currency_code, gl_account_id)
SELECT gen_random_uuid(), m.accounting_class, m.role, 'CZK', g.id
FROM (VALUES ('PENSION_MANAGEMENT_FEE_RECEIVABLE', 'PRINCIPAL', 'PS1310'),
             ('PENSION_MANAGEMENT_FEE_INCOME', 'FEE_INCOME', 'PS6110')) m(accounting_class, role, code)
JOIN gl_accounts g ON g.code = m.code
ON CONFLICT (accounting_class, role, currency_code) DO NOTHING;
DO $$ BEGIN
    IF (SELECT count(*) FROM accounting_class_gl_map m JOIN gl_accounts g ON g.id = m.gl_account_id
        WHERE (m.accounting_class, m.role, g.code) IN (
            ('PENSION_MANAGEMENT_FEE_RECEIVABLE', 'PRINCIPAL', 'PS1310'),
            ('PENSION_MANAGEMENT_FEE_INCOME', 'FEE_INCOME', 'PS6110')) AND g.is_enabled AND g.currency_code = 'CZK') <> 2 THEN
        RAISE EXCEPTION 'Company fee accounting requires the reviewed PS1310/PS6110 chart';
    END IF;
END $$;
