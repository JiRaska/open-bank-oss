-- Read fixtures for the standing-order read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The table is created by Flyway (V1), so this file only adds standing orders:
--   * one ACTIVE order at a FIXED id (00000000-0000-0000-0000-0000000000e2) probed by the
--     detail read, and
--   * 199 more ACTIVE orders for the same party so the by-party list pages real rows.
--
-- idempotency_key is UNIQUE, so every row gets its own key. A re-load into the same
-- throwaway database is a no-op-ish duplicate key rather than a second fixture set; the
-- gate loads fixtures exactly once.

-- Fixed detail-probe order first.
INSERT INTO standing_orders (id, idempotency_key, party_id, debit_account_id,
                             creditor_iban, creditor_name, creditor_bic,
                             amount_minor_units, currency, frequency,
                             remittance_info, start_date, next_execution_date, status)
VALUES ('00000000-0000-0000-0000-0000000000e2',
        'perf-standing-order-detail-fixture',
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        'CZ6508000000192000145399',
        'Perf Creditor',
        'GIBACZPX',
        100000,
        'CZK',
        'MONTHLY',
        'PERF STANDING ORDER DETAIL FIXTURE',
        CURRENT_DATE,
        CURRENT_DATE + 30,
        'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- 199 more orders for the by-party list.
INSERT INTO standing_orders (id, idempotency_key, party_id, debit_account_id,
                             creditor_iban, creditor_name, creditor_bic,
                             amount_minor_units, currency, frequency,
                             remittance_info, start_date, next_execution_date, status)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       'perf-standing-order-fixture-' || lpad(to_hex(g), 12, '0'),
       '00000000-0000-0000-0000-000000000001',
       '00000000-0000-0000-0000-000000000002',
       'CZ6508000000192000145399',
       'Perf Creditor',
       'GIBACZPX',
       100000,
       'CZK',
       'MONTHLY',
       'PERF STANDING ORDER FIXTURE',
       CURRENT_DATE,
       CURRENT_DATE + 30,
       'ACTIVE'
FROM generate_series(3, 201) AS g
ON CONFLICT (id) DO NOTHING;
