-- Read fixtures for the interest read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The rate config the accruals reference is seeded by Flyway (V10 seeds the CURRENT-product
-- config at the fixed id a0000000-0000-0000-0000-0000000000c2), so this file only adds rows:
--   * 200 interest_accruals for the fleet-wide fixture account
--     (00000000-0000-0000-0000-000000000002, PERF_ACCOUNT_ID in the k6 script) against the
--     CURRENT product (00000000-0000-0000-0000-0000000000c2) — 180 ACCRUING over the last 200
--     days and every 10th CAPITALIZED with capitalized_at set, so both the per-account accrual
--     ledger and the list-all lane page real rows; and
--   * one interest_capitalizations row covering the same period, so the capitalization-history
--     read returns a real entry instead of an empty list.
--
-- Accrual math matches the seeded config: annual_rate 0.025 (2.50 % p.a., V10) over ACT_365 on a
-- 100000.0000 balance gives daily_rate = 0.025/365 = 0.0000684932 and ~6.849320 accrued per day.
-- ids are deterministic (hex of g, zero-padded) so a re-load collides on the PK and is a no-op;
-- the gate loads fixtures exactly once into a throwaway database.
BEGIN;

-- 200 daily accruals for the fixture account. accrual_date steps back one day per row so the
-- UNIQUE(account_id, accrual_date, product_id) constraint holds; every 10th row is CAPITALIZED.
INSERT INTO interest_accruals (id, account_id, product_id, config_id, accrual_date, balance,
                               daily_rate, accrued_amount, currency, status, capitalized_at)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       '00000000-0000-0000-0000-000000000002'::uuid,
       '00000000-0000-0000-0000-0000000000c2',
       'a0000000-0000-0000-0000-0000000000c2'::uuid,
       CURRENT_DATE - g,
       100000.0000,
       0.0000684932,
       6.849320,
       'CZK',
       CASE WHEN g % 10 = 0 THEN 'CAPITALIZED'::accrual_status ELSE 'ACCRUING'::accrual_status END,
       CASE WHEN g % 10 = 0 THEN (CURRENT_DATE - g + 1)::timestamp ELSE NULL END
FROM generate_series(1, 200) AS g;

-- One capitalization covering the accrued period, so the capitalization-history read is non-empty.
INSERT INTO interest_capitalizations (id, account_id, product_id, period_from, period_to,
                                      total_accrued, capitalized_amount, currency)
VALUES ('00000000-0000-0000-0000-0000000000c1',
        '00000000-0000-0000-0000-000000000002'::uuid,
        '00000000-0000-0000-0000-0000000000c2',
        CURRENT_DATE - 200, CURRENT_DATE - 1,
        1369.864000, 1369.8600, 'CZK');

COMMIT;
