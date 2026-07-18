-- Seed the interest rate configuration for the retail SAVINGS product (product-catalog
-- 00000000-0000-0000-0000-0000000000c3, "CZK Savings"). Without an active rate config the accrual
-- run finds nothing to apply and every SAVINGS account accrues 0 — which is exactly the state the
-- sandbox was in (0 rate configs, 0 accruals) before the accrual engine was wired.
--
-- annual_rate is a DECIMAL FRACTION, not a percentage: the accrual formula is
--   daily_rate = annual_rate / day_count_divisor ; accrued = balance * daily_rate
-- so 4.00 % p.a. is stored as 0.040000 (100000 CZK -> ~10.96 CZK/day at ACT_365).
--
-- effective_from is backdated so historical accrual backfill for recently-opened accounts resolves
-- this config. Guarded by NOT EXISTS so a re-run (or a hand-seed on an already-migrated DB) is a
-- no-op rather than a second overlapping active config for the same product.
INSERT INTO interest_rate_configs (id, product_id, rate_type, annual_rate, min_balance, day_count, effective_from, active)
SELECT
    'a0000000-0000-0000-0000-0000000000c3'::uuid,
    '00000000-0000-0000-0000-0000000000c3',
    'FIXED',
    0.040000,
    0,
    'ACT_365',
    DATE '2025-01-01',
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM interest_rate_configs
    WHERE product_id = '00000000-0000-0000-0000-0000000000c3' AND active = TRUE
);
