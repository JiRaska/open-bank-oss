-- Multi-currency accounting (ADR-0025): per-currency FX position accounts so that a
-- cross-currency event self-balances WITHIN each currency, plus a P&L account for exchange
-- differences (kurzové rozdíly, ČÚS 108-110 / Decree 501/2002) and one for FX margin income.
--
-- Each GL account is single-currency. A customer pocket in a non-CZK currency therefore needs
-- its own per-currency deposit-control account. Stable UUIDs (last segment = account code) let
-- the payment saga and the cross-currency posting helper reference these deterministically.

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    -- Per-currency customer deposit control (liability to the customer, one per currency).
    ('a0000000-0000-0000-0000-000000002101', '2101', 'Customer Deposit Control EUR', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000002102', '2102', 'Customer Deposit Control USD', 'LIABILITY', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000002103', '2103', 'Customer Deposit Control GBP', 'LIABILITY', 'GBP', true, true),

    -- FX position accounts (devizová pozice), one per traded currency. The conversion routes
    -- through these so each currency leg nets to zero on its own.
    ('a0000000-0000-0000-0000-000000001990', '1990', 'FX Position CZK', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001991', '1991', 'FX Position EUR', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001992', '1992', 'FX Position USD', 'ASSET', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000001993', '1993', 'FX Position GBP', 'ASSET', 'GBP', true, true),

    -- Exchange-rate differences (kurzové rozdíly) — P&L in the functional currency (CZK),
    -- holds both gains and losses net. Daily revaluation books here (Phase 3).
    ('a0000000-0000-0000-0000-000000005900', '5900', 'Exchange Rate Differences', 'INCOME', 'CZK', true, true),

    -- FX margin income (the markup the bank charges on customer conversions).
    ('a0000000-0000-0000-0000-000000004002', '4002', 'FX Margin Income', 'INCOME', 'CZK', true, true);
