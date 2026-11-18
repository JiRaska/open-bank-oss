-- Read fixtures for the pension read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The pension tables start empty (no Flyway seed), so this file adds rows:
--   * 20 ACTIVE contracts and 200 CZK contributions for them spread over the current year (the
--     aggregates probe refuses to sum across currencies, so fixtures stay CZK-only on
--     purpose), so the participant-aggregates read reduces real rows;
--   * 3 return reports and 10 state-contribution returns (mix of DUE and REPORTED, the
--     REPORTED ones referencing a filed report per the table's reported-in-report CHECK), so
--     the returns and return-reports reads page real rows.
--
-- (The annuity provider directory is a staff-only route — ContractAccessGuard requires a human
-- staff principal and rejects the anonymous lane identity — so it is deliberately not probed
-- and needs no fixtures.)
--
-- ids are deterministic (hex of g, zero-padded) so a re-load collides on the PK/UNIQUE
-- constraints and is a no-op; the gate loads fixtures exactly once into a throwaway database.
-- Statuses, sources and causes stay inside the domain enums so the reads parse every row.
BEGIN;

-- 20 ACTIVE contracts on the fleet-wide fixture party. product_line/jurisdiction/frequency
-- stay inside the domain enums; every ACTIVE contract carries its start_date (table CHECK).
INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction,
                               pack_version, provider_entity_id, provider_type,
                               participant_birth_date, status, contribution_amount,
                               employer_contribution_amount, contribution_currency,
                               contribution_frequency, start_date, idempotency_key,
                               created_at, updated_at)
SELECT g,
       ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       '00000000-0000-0000-0000-000000000001'::uuid,
       CASE WHEN g % 2 = 0 THEN 'DPS' ELSE 'DIP' END,
       'CZ',
       1,
       'a0000000-0000-0000-0000-000000000001'::uuid,
       'INTERNAL',
       DATE '1980-01-01' + g,
       'ACTIVE',
       3000.0000,
       1000.0000,
       'CZK',
       'MONTHLY',
       CURRENT_DATE - (g * 30),
       'perf-contract-' || g,
       NOW() - (g * 30 || ' days')::interval,
       NOW() - (g * 30 || ' days')::interval + interval '1 hour'
FROM generate_series(1, 20) AS g;

-- 200 CZK contributions across the 20 contracts, spread over the current year so the
-- period aggregates reduce real rows. EMPLOYER-sourced rows name an employer party (table
-- CHECK); the rest leave it NULL.
INSERT INTO pension_contributions (id, contract_id, payment_id, source, channel, amount,
                                   currency, value_date, employer_party_id, received_at)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(g), 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(to_hex(1 + (g % 20)), 12, '0'))::uuid,
       'perf-pay-' || g,
       (ARRAY['PARTICIPANT', 'EMPLOYER', 'STATE', 'TRANSFER_IN'])[1 + g % 4],
       'BANK_TRANSFER',
       500.0000 + (g % 50) * 100,
       'CZK',
       DATE '2026-01-01' + (g % 300),
       CASE WHEN g % 4 = 1 THEN '00000000-0000-0000-0000-0000000000c7'::uuid ELSE NULL END,
       NOW() - (g % 300 || ' days')::interval
FROM generate_series(1, 200) AS g;

-- 3 filed return reports (month is CHAR(7) 'YYYY-MM'). V16 made currency/total NOT NULL and
-- derives total from the filed header's 7th field, so the payload carries a real header line.
-- return_ids lists the returns filed in each report — the ids the REPORTED returns below link
-- back (report 1 <- returns 6,9; report 2 <- returns 7,10; report 3 <- returns 5,8).
INSERT INTO pension_return_reports (id, month, return_ids, payload, channel_reference,
                                    result_applied, created_at, currency, total)
SELECT ('00000000-0000-0000-0002-' || lpad(to_hex(g), 12, '0'))::uuid,
       to_char(DATE '2026-01-01' + ((g - 1) * 31), 'YYYY-MM'),
       CASE g
           WHEN 1 THEN '00000000-0000-0000-0003-000000000006,00000000-0000-0000-0003-000000000009'
           WHEN 2 THEN '00000000-0000-0000-0003-000000000007,00000000-0000-0000-0003-00000000000a'
           ELSE '00000000-0000-0000-0003-000000000005,00000000-0000-0000-0003-000000000008'
       END,
       'H;RETURNS-PERF;RETURNS;12345678;' ||
           to_char(DATE '2026-01-01' + ((g - 1) * 31), 'YYYY-MM') || ';2;' || (1500.00 * g)::text ||
           E'\n{"report":"PERF ' || g || '"}',
       'PERF-CHANNEL-' || g,
       FALSE,
       NOW() - (g * 20 || ' days')::interval,
       'CZK',
       1500.00 * g
FROM generate_series(1, 3) AS g;

-- 10 state-contribution returns: 4 DUE (no report required), 6 REPORTED against the reports
-- above. source_key is UNIQUE; amount stays positive (table CHECK).
INSERT INTO pension_state_contribution_returns (id, contract_id, claim_id, cause, amount,
                                                currency, discovered_on, due_by, source_key,
                                                status, report_id, created_at, updated_at)
SELECT ('00000000-0000-0000-0003-' || lpad(to_hex(g), 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(to_hex(1 + (g % 20)), 12, '0'))::uuid,
       NULL,
       CASE WHEN g % 2 = 0 THEN 'CONTRACT_TERMINATED' ELSE 'INELIGIBILITY_DISCOVERED' END,
       500.0000 + g * 100,
       'CZK',
       CURRENT_DATE - (g * 10),
       CURRENT_DATE + 30,
       'perf-return-' || g,
       CASE WHEN g <= 4 THEN 'DUE' ELSE 'REPORTED' END,
       CASE WHEN g <= 4 THEN NULL
            ELSE ('00000000-0000-0000-0002-' || lpad(to_hex(1 + (g % 3)), 12, '0'))::uuid END,
       NOW() - (g * 10 || ' days')::interval,
       NOW() - (g * 10 || ' days')::interval + interval '2 hours'
FROM generate_series(1, 10) AS g;

COMMIT;
