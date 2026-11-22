-- Read fixtures for the clearing read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The batch-detail probe is id-scoped (PERF_BATCH_ID, default
-- 00000000-0000-0000-0000-000000000002 in clearing-read-baseline.js), so an empty database would
-- time a 404 instead of the handler. This file seeds:
--   * one SETTLED SEPA_SCT batch at that id with consistent totals (200 items, 200 x 1000.0000
--     debit and credit, zero net position) — distinct batch_reference so a re-load collides on
--     the UNIQUE constraint and is a no-op; and
--   * its 200 clearing_items, so the detail read pages a realistic batch rather than a header
--     over zero rows. payment_id is unique per item (V9), so ids are deterministic (hex of g,
--     zero-padded) and a re-load fails loudly on the PK instead of silently doubling rows —
--     the gate loads fixtures exactly once into a throwaway database.
--
-- The UNASSIGNED sentinel batch (V5) is untouched; these items belong to their own batch, so no
-- later clearing cycle would ever try to re-assign them (they are SETTLED, not PENDING).
BEGIN;

INSERT INTO clearing_batches (id, batch_reference, rail, settlement_type, status,
                              total_debit, total_credit, net_position, currency, item_count,
                              cycle_id, settlement_date, settled_at)
VALUES ('00000000-0000-0000-0000-000000000002',
        'PERF-READ-BASELINE',
        'SEPA_SCT',
        'NET',
        'SETTLED',
        200000.0000,
        200000.0000,
        0.0000,
        'EUR',
        200,
        'CYCLE-SEPA_SCT-20260101-0001',
        DATE '2026-01-01',
        '2026-01-01T14:05:00Z');

INSERT INTO clearing_items (id, batch_id, payment_id, payment_reference,
                            debtor_iban, creditor_iban, debtor_bic, creditor_bic,
                            amount, currency, status, value_date, end_to_end_id,
                            remittance_info, rail)
SELECT ('00000000-0000-4000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,
       '00000000-0000-0000-0000-000000000002',
       ('10000000-0000-4000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,
       'PERF-ITEM-' || lpad(g::text, 4, '0'),
       'CZ6508000000192000145399',
       'CZ8208000000001016582223',
       'GIBACZPX',
       'AIRACZPP',
       1000.0000,
       'EUR',
       'SETTLED',
       DATE '2026-01-01',
       'PERF-E2E-' || lpad(g::text, 4, '0'),
       'read baseline fixture',
       'SEPA_SCT'
FROM generate_series(1, 200) AS g;

COMMIT;
