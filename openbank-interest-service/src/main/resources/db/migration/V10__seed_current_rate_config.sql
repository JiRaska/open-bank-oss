-- Make the CURRENT (everyday payment) account interest-bearing too, not just SAVINGS.
--
-- Why: the onboarding bonus (and every retail customer's working balance) lands on the CURRENT
-- account (product 00000000-0000-0000-0000-0000000000c2), NOT savings — savings accounts are
-- opened but sit empty unless the customer moves money there by hand. With interest seeded only on
-- SAVINGS (V9), virtually every customer earned nothing because their money is on CURRENT. Seeding a
-- rate for the CURRENT product makes interest apply to the account the customer actually holds a
-- balance on — systematically, for every current and future account, no per-account backfill.
--
-- Two-tier by product, as a real bank would: CURRENT 2.50 % p.a. (an interest-bearing everyday
-- account), SAVINGS 4.00 % p.a. (the premium product, V9). annual_rate is a DECIMAL FRACTION.
-- Backdated + NOT EXISTS-guarded, same shape as V9.
INSERT INTO interest_rate_configs (id, product_id, rate_type, annual_rate, min_balance, day_count, effective_from, active)
SELECT
    'a0000000-0000-0000-0000-0000000000c2'::uuid,
    '00000000-0000-0000-0000-0000000000c2',
    'FIXED',
    0.025000,
    0,
    'ACT_365',
    DATE '2025-01-01',
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM interest_rate_configs
    WHERE product_id = '00000000-0000-0000-0000-0000000000c2' AND active = TRUE
);
