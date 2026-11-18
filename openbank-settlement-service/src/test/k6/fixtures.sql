-- Read fixtures for the settlement read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The settlement tables start empty (no Flyway seed), so this file adds rows:
--   * 200 settlements across the PENDING/DEBITED/CREDITED/BOOKED/REJECTED lifecycle between the
--     fleet-wide fixture account (00000000-0000-0000-0000-000000000002, payer) and a fixed
--     payee account, so the status read probes a real aggregate; and
--   * 30 PENDING operator approvals (the queue's live subset), plus the fixed detail-probe
--     approval (00000000-0000-0000-0000-0000000000e2, PERF_APPROVAL_ID) — PENDING rows carry no
--     decision columns, matching the table's decision-iff-not-pending CHECK.
--
-- ids are deterministic (hex of g, zero-padded; the two probe rows at fixed ids) so a re-load
-- collides on the PK and is a no-op; the gate loads fixtures exactly once into a throwaway
-- database. Statuses stay inside the SettlementStatus enum so the reads parse every row.
BEGIN;

-- 200 settlements; g = 1 doubles as the fixed detail probe (PERF_SETTLEMENT_ID =
-- 00000000-0000-0000-0000-0000000000e1), a BOOKED settlement.
INSERT INTO settlements (id, payer_account_id, payee_account_id, amount, currency, status,
                         created_at, updated_at)
SELECT CASE WHEN g = 1 THEN '00000000-0000-0000-0000-0000000000e1'::uuid
            ELSE ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid END,
       '00000000-0000-0000-0000-000000000002'::uuid,
       '00000000-0000-0000-0000-0000000000c9'::uuid,
       1000.0000 + g,
       CASE WHEN g % 4 = 0 THEN 'EUR' ELSE 'CZK' END,
       CASE
           WHEN g % 6 = 0 THEN 'REJECTED'
           WHEN g % 5 = 0 THEN 'PENDING'
           WHEN g % 4 = 0 THEN 'BOOKED'
           WHEN g % 3 = 0 THEN 'CREDITED'
           ELSE 'DEBITED'
       END,
       NOW() - (g || ' hours')::interval,
       NOW() - (g || ' hours')::interval + interval '30 minutes'
FROM generate_series(1, 200) AS g;

-- 30 live PENDING approvals; g = 1 doubles as the fixed detail probe (PERF_APPROVAL_ID =
-- 00000000-0000-0000-0000-0000000000e2). PENDING rows carry NULL decision columns per the
-- table CHECK; expires_at stays ahead of created_at.
INSERT INTO settlement_operator_approvals (id, action, resource_id, maker_id, status,
                                           created_at, expires_at, request_fingerprint,
                                           summary)
SELECT CASE WHEN g = 1 THEN '00000000-0000-0000-0000-0000000000e2'::uuid
            ELSE ('00000000-0000-0000-0000-' || lpad(to_hex(1000 + g), 12, '0'))::uuid END,
       'SETTLEMENT_REVERSE',
       '00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'),
       'perf-maker',
       'PENDING',
       NOW() - (g || ' hours')::interval,
       NOW() + interval '7 days',
       lpad(to_hex(g), 64, '0'),
       'PERF approval fixture ' || g
FROM generate_series(1, 30) AS g;

COMMIT;
