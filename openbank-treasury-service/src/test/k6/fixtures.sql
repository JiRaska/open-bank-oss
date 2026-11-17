-- Read fixtures for the treasury read-baseline lane (.github/workflows/perf-gate.yml).
--
-- Counterparties (CNB, SIMBK-A/B/C) and credit measures are seeded by Flyway (V1, V28), so this
-- file only adds deals:
--   * 60 money-market deals across the three synthetic bank counterparties in BOOKED, SETTLED
--     and MATURED states and both currencies, so the dealer blotter, the filtered view, the
--     events calendar, the daily positions and the limit-utilisation reads all page real rows.
--     (The ČNB deposit facility product is deliberately NOT fixture-seeded: its domain
--     invariants are overnight-only and CZK-only, which would buy this lane a business-day
--     calendar dependency for zero extra signal.)
--
-- ids are deterministic (deals.id = g; deal_id = hex of g, zero-padded) so a re-load collides
-- on the PK/UNIQUE and is a no-op; the gate loads fixtures exactly once into a throwaway
-- database. The four-eyes constraints hold: the approver differs from the creator and is a
-- HUMAN, matching the domain rule that an approval is always a human's (ADR-0315 D3).
BEGIN;

INSERT INTO deals (id, deal_id, product, counterparty_id, currency, principal, rate,
                   trade_date, value_date, maturity_date, state,
                   created_by, created_by_type, submitted_by, submitted_by_type,
                   approved_by, approved_by_type, created_at, updated_at)
SELECT g,
       ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       CASE WHEN g % 2 = 0 THEN 'MM_PLACEMENT' ELSE 'MM_BORROWING' END,
       CASE
            WHEN g % 3 = 0 THEN 'SIMBK-C'
            WHEN g % 3 = 1 THEN 'SIMBK-A'
            ELSE 'SIMBK-B' END,
       CASE WHEN g % 3 = 0 THEN 'EUR' ELSE 'CZK' END,
       1000000.00 + g * 10000,
       0.035000,
       CURRENT_DATE - (g % 120),
       CURRENT_DATE - (g % 120) + 1,
       CURRENT_DATE + 30 + (g % 180),
       CASE
           WHEN g % 7 = 0 THEN 'MATURED'
           WHEN g % 3 = 0 THEN 'SETTLED'
           ELSE 'BOOKED'
       END,
       'perf-dealer', 'HUMAN',
       'perf-dealer', 'HUMAN',
       'perf-approver', 'HUMAN',
       NOW() - (g % 120 || ' days')::interval,
       NOW() - (g % 120 || ' days')::interval + interval '1 hour'
FROM generate_series(1, 60) AS g;

COMMIT;
