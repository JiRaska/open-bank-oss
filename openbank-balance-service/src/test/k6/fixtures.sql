-- SPDX-License-Identifier: Apache-2.0
-- Perf read-baseline fixture for openbank-balance-service (perf-gate.yml lane).
--
-- Loaded into the throwaway PostgreSQL after the service boots (Flyway has created the
-- schema by then) and discarded with the container. Read-only-lane setup: the seeded rows
-- are never mutated by the k6 script, and without them the id-scoped probes would time a
-- 404 (rejection evidence, not a handler baseline). The account id is the fleet-wide
-- fixture id also used by the account-overview synthetic journey.
INSERT INTO balances (account_id, currency, booked_amount, available_amount, reserved_amount, pending_amount)
VALUES ('00000000-0000-0000-0000-0000000000c2', 'CZK', 1000.0000, 1000.0000, 0, 0);

-- reconciliation/latest 404s when no run was ever persisted; one benign run (no drift)
-- makes the route measurable. currencies is the JSON of ReconciliationReport.currencies.
INSERT INTO balance_reconciliation (as_of, generated_at, tolerance, has_drift, drifted_currencies, currencies)
VALUES (
    CURRENT_DATE,
    NOW(),
    0.0100,
    false,
    '',
    '[{"currency":"CZK","ledgerControlBalance":1000,"subLedgerBookedSum":1000,"difference":0,"withinTolerance":true}]'
);
