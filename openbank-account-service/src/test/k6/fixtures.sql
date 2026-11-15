-- SPDX-License-Identifier: Apache-2.0
-- Perf read-baseline fixture for openbank-account-service (perf-gate.yml lane).
--
-- Loaded into the throwaway PostgreSQL after the service boots (Flyway has created the
-- schema by then) and discarded with the container. Read-only-lane setup: the seeded rows
-- are never mutated by the k6 script, and without them the id-scoped probes would time a
-- 404 (rejection evidence, not a handler baseline).
--
-- Identity contract with the lane:
--   * the detail probe follows PERF_ACCOUNT_ID, which the gate exports as
--     00000000-0000-0000-0000-0000000000c2 — seeded below as the first row;
--   * the list probe follows PERF_PARTY_ID, for which the script's default
--     00000000-0000-0000-0000-000000000001 is used (the gate does not inject one) — all
--     five rows belong to that party so the list page is non-trivial;
--   * the search probe queries q=CZ against account_number, so every seeded IBAN starts
--     with the CZ country prefix.
INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code, status)
VALUES
    ('00000000-0000-0000-0000-0000000000c2', 'CZ6508000000192000145399', 'CURRENT',
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'CZK', 'ACTIVE'),
    ('00000000-0000-0000-0000-0000000000c3', 'CZ3808000000192000145400', 'CURRENT',
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'CZK', 'ACTIVE'),
    ('00000000-0000-0000-0000-0000000000c4', 'CZ1108000000192000145401', 'SAVINGS',
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a2', 'CZK', 'ACTIVE'),
    ('00000000-0000-0000-0000-0000000000c5', 'CZ8108000000192000145402', 'CURRENT',
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'EUR', 'ACTIVE'),
    ('00000000-0000-0000-0000-0000000000c6', 'CZ5408000000192000145403', 'CURRENT',
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'CZK', 'DORMANT');
