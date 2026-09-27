-- SPDX-License-Identifier: Apache-2.0
-- #10896 (ADR-0315 treasury products gap): the liability account for openbank-treasury-service's
-- CNB_LOMBARD deals — overnight borrowing from the ČNB marginal lending (lombard) facility. Fixed id
-- per the V29 convention (a0000000-0000-0000-0000-00000000<code>); CZK only, like 1510.
--
-- | code | name                               | type      | ccy |
-- | 2320 | Borrowings from CNB (lombard)      | LIABILITY | CZK |
--
-- Rollback (safe only while no journal_line references it — check first):
--   DELETE FROM gl_accounts WHERE code = '2320';

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000002320', '2320', 'Borrowings from CNB (lombard)', 'LIABILITY', 'CZK', true, true);
