-- Read fixtures for the transaction read-baseline lane (.github/workflows/perf-gate.yml).
--
-- 1000 COMPLETED DEBIT transactions on the fleet-wide fixture account
-- (PERF_ACCOUNT_ID=00000000-0000-0000-0000-0000000000c2) spread over the last 90
-- days, so the list endpoint pages real rows and the search endpoint exercises a
-- non-trivial query plan instead of an empty partition scan.
--
-- The table is PARTITION BY RANGE(booking_date); inserts through the parent route
-- into the 2025/2026 partitions. reference_number and idempotency_key are unique per
-- (key, booking_date) — 'PERF-<g>' / 'perf-txn-<g>' are distinct per row, so both
-- unique constraints hold regardless of the day collision pattern.
--
-- Columns follow V1__init_transactions.sql plus the later ALTERs (source_iban,
-- counterparty_name); only NOT NULL columns without defaults are listed.
INSERT INTO transactions (
    reference_number,
    type,
    source_account_id,
    amount,
    currency_code,
    base_amount,
    base_currency_code,
    status,
    description,
    value_date,
    booking_date,
    initiated_at,
    completed_at,
    idempotency_key,
    source_iban,
    counterparty_name
)
SELECT
    'PERF-' || g,
    'DEBIT',
    '00000000-0000-0000-0000-0000000000c2'::uuid,
    100 + (g % 500),
    'CZK',
    100 + (g % 500),
    'CZK',
    'COMPLETED',
    'PERF CARD PAYMENT ' || g,
    CURRENT_DATE - (g % 90),
    CURRENT_DATE - (g % 90),
    now() - ((g % 90) || ' days')::interval,
    now() - ((g % 90) || ' days')::interval + '1 second',
    'perf-txn-' || g,
    'CZ650800000000' || lpad(g::text, 10, '0'),
    'Perf Counterparty ' || (g % 7)
FROM generate_series(1, 1000) AS g;
