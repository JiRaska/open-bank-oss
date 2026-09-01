-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Capital-structure accounts required by the COREP C 01.00 own-funds mapper. These are accounting
-- source accounts, not calculated regulatory facts: finrep-service derives CET1, Tier 1 and own
-- funds from their posted balances and never plugs capital as assets minus liabilities.

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000006000', '6000', 'CET1 Capital Instruments', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000006010', '6010', 'CET1 Share Premium', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000006020', '6020', 'Retained Earnings', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000006030', '6030', 'Other Reserves', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000006040', '6040', 'CET1 Regulatory Deductions', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000006050', '6050', 'Additional Tier 1 Capital', 'EQUITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000006060', '6060', 'Tier 2 Capital', 'EQUITY', 'CZK', true, true);

-- Rollback:
--   DELETE FROM gl_accounts WHERE code IN ('6000','6010','6020','6030','6040','6050','6060');
-- Safe only before journal lines reference the accounts; afterwards the FK deliberately blocks
-- deletion of accounting source structure used by a rendered regulatory report.
