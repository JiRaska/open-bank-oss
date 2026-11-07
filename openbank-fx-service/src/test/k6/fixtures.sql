-- SPDX-License-Identifier: Apache-2.0
-- Perf read-baseline fixture for openbank-fx-service (perf-gate.yml lane).
--
-- Loaded into the throwaway PostgreSQL after the service boots (Flyway has created the
-- schema by then) and discarded with the container. Read-only-lane setup: the seeded rows
-- are never mutated by the k6 script, and without them the pair/history probes would time
-- a 404 (rejection evidence, not a handler baseline). The pair matches the script's
-- default PERF_FX_BASE/PERF_FX_QUOTE (EUR/CZK).
--
-- One current row (validity window brackets now) answers /rates and /rates/EUR/CZK; a
-- short history answers the /history probe with a real page instead of an empty one.
INSERT INTO fx_rates (id, base_currency, quote_currency, bid_rate, ask_rate, rate_type, source, valid_from, valid_to)
VALUES
    ('00000000-0000-0000-0000-0000000000d1', 'EUR', 'CZK', 24.90000000, 25.10000000, 'SPOT', 'ECB',
     NOW() - INTERVAL '2 days', NOW() + INTERVAL '1 day'),
    ('00000000-0000-0000-0000-0000000000d2', 'EUR', 'CZK', 24.85000000, 25.05000000, 'SPOT', 'ECB',
     NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days'),
    ('00000000-0000-0000-0000-0000000000d3', 'EUR', 'CZK', 24.80000000, 25.00000000, 'SPOT', 'ECB',
     NOW() - INTERVAL '4 days', NOW() - INTERVAL '3 days');
