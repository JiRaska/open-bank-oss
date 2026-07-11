-- Per-currency cash-clearing accounts (issue #747). PaymentJournalFactory's cash-clearing leg
-- (the bank-side leg of a one-sided inbound/outbound payment, i.e. anything that isn't a
-- same-currency internal transfer between two customer pockets) posted against the single
-- CZK-only 'Customer Cash Clearing' account (1100, seeded in V3) regardless of the payment's
-- actual currency. LedgerService.postJournal rejects a line whose currencyCode doesn't match its
-- GL account's declared currency (422) — confirmed live: a EUR CREDIT transaction fails there,
-- the identical CZK one succeeds. V5 already established this exact per-currency pattern for
-- deposit-control and FX-position accounts; cash-clearing was the one leaf that never got it.
--
-- Same numbering convention as V5's deposit-control accounts (XX01/XX02/XX03 = EUR/USD/GBP).

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000001101', '1101', 'Customer Cash Clearing EUR', 'ASSET', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000001102', '1102', 'Customer Cash Clearing USD', 'ASSET', 'USD', true, true),
    ('a0000000-0000-0000-0000-000000001103', '1103', 'Customer Cash Clearing GBP', 'ASSET', 'GBP', true, true);
