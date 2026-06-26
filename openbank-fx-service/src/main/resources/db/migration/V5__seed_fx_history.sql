-- Seed 90 days of synthetic INTERNAL commercial rates so the history chart is populated
-- on first deploy (sandbox / CI). Each pair uses a SIN-based drift for visual realism.
-- Historical rows (s.n >= 1) expire at the end of the seeded day; today's row stays
-- open for 1 year so it appears in the live rate-sheet until the first manual publication.
INSERT INTO fx_rates (id, base_currency, quote_currency, bid_rate, ask_rate,
                      rate_type, source, valid_from, valid_to, created_at)
SELECT
    gen_random_uuid(),
    r.base,
    'CZK',
    ROUND((r.mid * (1 - r.spread / 2.0) * (1.0 + 0.018 * SIN(s.n * 0.29 + r.phase)))::NUMERIC, 8),
    ROUND((r.mid * (1 + r.spread / 2.0) * (1.0 + 0.018 * SIN(s.n * 0.29 + r.phase)))::NUMERIC, 8),
    'SPOT',
    'INTERNAL',
    (CURRENT_DATE - s.n * INTERVAL '1 day')::TIMESTAMP WITH TIME ZONE,
    (CURRENT_DATE - s.n * INTERVAL '1 day' + INTERVAL '1 day')::TIMESTAMP WITH TIME ZONE,
    NOW() - s.n * INTERVAL '1 day'
FROM generate_series(1, 89) AS s(n)
CROSS JOIN (VALUES
    ('EUR', 25.05::NUMERIC, 0.020::NUMERIC, 0.00::NUMERIC),
    ('USD', 23.10::NUMERIC, 0.022::NUMERIC, 1.10::NUMERIC),
    ('GBP', 29.40::NUMERIC, 0.024::NUMERIC, 2.30::NUMERIC),
    ('CHF', 26.20::NUMERIC, 0.020::NUMERIC, 0.70::NUMERIC),
    ('PLN',  5.820::NUMERIC, 0.026::NUMERIC, 1.50::NUMERIC),
    ('HUF',  0.0630::NUMERIC, 0.035::NUMERIC, 3.10::NUMERIC)
) AS r(base, mid, spread, phase);

-- Today's open INTERNAL rate (1-year validity; replaced by manual publication when live)
INSERT INTO fx_rates (id, base_currency, quote_currency, bid_rate, ask_rate,
                      rate_type, source, valid_from, valid_to, created_at)
VALUES
    (gen_random_uuid(), 'EUR', 'CZK', 24.80, 25.30, 'SPOT', 'INTERNAL',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '365 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'USD', 'CZK', 22.85, 23.35, 'SPOT', 'INTERNAL',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '365 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'GBP', 'CZK', 29.05, 29.75, 'SPOT', 'INTERNAL',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '365 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'CHF', 'CZK', 25.90, 26.50, 'SPOT', 'INTERNAL',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '365 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'PLN', 'CZK', 5.74, 5.90, 'SPOT', 'INTERNAL',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '365 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'HUF', 'CZK', 0.061, 0.065, 'SPOT', 'INTERNAL',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '365 days')::TIMESTAMP WITH TIME ZONE, NOW());

-- Seed today's CNB reference rates (approximate values; real scheduler ingest at 14:40 is idempotent
-- because CnbRateIngestionService checks for existing (source, pair, validFrom) before inserting).
-- validTo = +3 days covers weekends and public holidays until the scheduler re-runs.
INSERT INTO fx_rates (id, base_currency, quote_currency, bid_rate, ask_rate,
                      rate_type, source, valid_from, valid_to, created_at)
VALUES
    (gen_random_uuid(), 'EUR', 'CZK', 25.00, 25.00, 'INDICATIVE', 'CNB',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '3 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'USD', 'CZK', 23.07, 23.07, 'INDICATIVE', 'CNB',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '3 days')::TIMESTAMP WITH TIME ZONE, NOW()),
    (gen_random_uuid(), 'GBP', 'CZK', 29.31, 29.31, 'INDICATIVE', 'CNB',
     CURRENT_DATE::TIMESTAMP WITH TIME ZONE,
     (CURRENT_DATE + INTERVAL '3 days')::TIMESTAMP WITH TIME ZONE, NOW());
