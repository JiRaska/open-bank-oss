-- Read fixtures for the sdd read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The mandate vault starts empty (no Flyway seed), so this file adds rows:
--   * 200 sdd_mandate rows for the fleet-wide fixture account (00000000-0000-0000-0000-
--     000000000002, PERF_ACCOUNT_ID in the k6 script), across the ACTIVE/SUSPENDED/CANCELLED/
--     EXPIRED lifecycle and both schemes (CORE/B2B) and sequence types (OOFF/FRST/RCUR/FNAL),
--     so the mandate list, the per-account view and the newest-mandates queue all page real
--     rows; and
--   * the fixed detail-probe mandate (00000000-0000-0000-0000-0000000000d1, PERF_MANDATE_ID)
--     is g = 1: ACTIVE, CORE, RCUR, with collection history — the refund-assessment probe
--     needs a live, non-B2B mandate with a last_collection_date inside the computable window.
--
-- (creditor_identifier, umr) is UNIQUE, so both are derived from g. ids are deterministic
-- (hex of g, zero-padded) so a re-load collides on the PK and is a no-op; the gate loads
-- fixtures exactly once into a throwaway database. Statuses, schemes and sequence types stay
-- inside the domain enums so the reads parse every row.
BEGIN;

INSERT INTO sdd_mandate (id, account_id, debtor_iban, creditor_identifier, umr, scheme,
                         sequence_type, creditor_name, debtor_name, signature_date, status,
                         b2b_confirmed, last_collection_date, last_pre_notification_date,
                         created_at, amendments)
SELECT CASE WHEN g = 1 THEN '00000000-0000-0000-0000-0000000000d1'::uuid
            ELSE ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid END,
       '00000000-0000-0000-0000-000000000002'::uuid,
       'CZ' || lpad(g::text, 20, '0') || '0000000001',
       'PERF-CRED-' || lpad(g::text, 8, '0'),
       'PERF-UMR-' || lpad(g::text, 8, '0'),
       CASE WHEN g % 5 = 0 THEN 'B2B' ELSE 'CORE' END,
       (ARRAY['OOFF', 'FRST', 'RCUR', 'FNAL'])[1 + g % 4],
       'PERF CREDITOR ' || g,
       'PERF DEBTOR',
       CURRENT_DATE - 200 + g,
       CASE
           WHEN g % 8 = 0 THEN 'CANCELLED'
           WHEN g % 7 = 0 THEN 'EXPIRED'
           WHEN g % 6 = 0 THEN 'SUSPENDED'
           WHEN g % 5 = 0 THEN 'PENDING_CONFIRMATION'
           ELSE 'ACTIVE'
       END,
       g % 5 = 0,
       CASE WHEN g % 8 = 0 OR g % 7 = 0 OR g % 6 = 0 OR g % 5 = 0 THEN NULL ELSE CURRENT_DATE - (g % 90) END,
       CASE WHEN g % 8 = 0 OR g % 7 = 0 OR g % 6 = 0 OR g % 5 = 0 THEN NULL ELSE CURRENT_DATE - (g % 90) - 3 END,
       NOW() - (200 - g || ' days')::interval,
       '[]'
FROM generate_series(1, 200) AS g;

COMMIT;
