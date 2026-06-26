-- Daily FX revaluation (ADR-0031, refines ADR-0025 #3). Per-currency CZK counter-value accounts
-- that hold the cumulative CZK mark-to-ČNB of each foreign FX position (199x). The daily
-- revaluation entry is a pure CZK overlay: it moves a counter-value account against the
-- exchange-rate-differences P&L (5900) and never touches the foreign 199x position, so the
-- per-currency balancing invariant (ADR-0025 #1) holds and the entry self-balances in CZK.
--
-- Each account is CZK-denominated (the functional currency) and ASSET-typed: a long foreign
-- position carries a positive (debit) CZK value here. When a position closes (199x → 0) the mark
-- target → 0 and the accumulated unrealized amount flushes back through 5900 automatically.
-- Stable UUIDs (last segment = account code) mirror the V5 convention.

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000001995', '1995', 'FX Position Counter-Value EUR (CZK)', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001996', '1996', 'FX Position Counter-Value USD (CZK)', 'ASSET', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000001997', '1997', 'FX Position Counter-Value GBP (CZK)', 'ASSET', 'CZK', true, true);
