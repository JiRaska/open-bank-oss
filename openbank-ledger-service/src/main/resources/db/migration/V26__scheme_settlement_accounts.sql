-- SPDX-License-Identifier: Apache-2.0
-- Per-currency scheme-settlement (nostro-at-scheme) accounts (ADR-0281, issue #8361). The
-- clearing net-settlement leg moves a settled batch's volume between the Customer Cash Clearing
-- accounts (V3/V14, codes 1100-1103) and the bank's settlement account at the scheme — the leg
-- that was never posted while clearing settled only against the simulator. LedgerService.postJournal
-- rejects a line whose currencyCode doesn't match the GL account's declared currency (422, the
-- exact failure V14 fixed for cash-clearing), so these are per-currency from the start.
--
-- Same numbering convention as V14's cash-clearing accounts (XX01/XX02/XX03 = EUR/USD/GBP, the
-- XX00 entry CZK), in the next free 111x block.
--
-- Rollback: DELETE FROM gl_accounts WHERE code IN ('1110','1111','1112','1113');
-- (safe only while no journal_line references them — check first on any non-fresh environment).

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000001110', '1110', 'Scheme Settlement CZK', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001111', '1111', 'Scheme Settlement EUR', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001112', '1112', 'Scheme Settlement USD', 'ASSET', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000001113', '1113', 'Scheme Settlement GBP', 'ASSET', 'GBP', true, true);
