-- Read fixtures for the card-processing read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The card-processing tables start empty (no Flyway seed), so this file adds rows for one fixed
-- probe card (00000000-0000-0000-0000-0000000000d1, PERF_CARD_ID in the k6 script):
--   * 200 card_authorizations across the APPROVED/DECLINED/CLEARED/REVERSED lifecycle states,
--     every declined row naming its decline_reason (the table constraint demands the pairing), so
--     the per-card authorisation history pages real rows and the fixed detail probe
--     (00000000-0000-0000-0000-0000000000d2, PERF_AUTH_ID) has a live row;
--   * 1 card_dispute_cases row against that fixed authorisation with its evidence row, so the
--     dispute detail probe (00000000-0000-0000-0000-0000000000d3, PERF_DISPUTE_ID) and the
--     per-card dispute list answer with real payloads; and
--   * 3 card_network_tokens rows (ACTIVE/SUSPENDED/DELETED), so the token mirror read answers
--     with a real list.
--
-- ids are deterministic (hex of g, zero-padded) so a re-load collides on the PK/UNIQUE indexes
-- and is a no-op; the gate loads fixtures exactly once into a throwaway database. Channels stay
-- inside the PresentmentChannel enum and categories inside the card-issuance taxonomy, so the
-- domain reads parse every row.
BEGIN;

-- 200 authorisations for the probe card. g = 1 doubles as the fixed detail-probe row
-- (PERF_AUTH_ID = 00000000-0000-0000-0000-0000000000d2): CLEARED, with a network reference and
-- partial clearing so every response field is exercised.
INSERT INTO card_authorizations (id, card_id, account_id, party_id, amount_minor_units,
                                 currency_code, channel, mcc, merchant_name, merchant_country,
                                 status, category, decline_reason,
                                 cleared_amount_minor_units, network_reference,
                                 idempotency_key, authorized_at, expires_at, updated_at)
SELECT CASE WHEN g = 1 THEN '00000000-0000-0000-0000-0000000000d2'::uuid
            ELSE ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid END,
       '00000000-0000-0000-0000-0000000000d1'::uuid,
       '00000000-0000-0000-0000-000000000002'::uuid,
       '00000000-0000-0000-0000-000000000001'::uuid,
       1000 + g * 100,
       CASE WHEN g % 4 = 0 THEN 'EUR' ELSE 'CZK' END,
       (ARRAY['CONTACTLESS', 'ONLINE', 'ATM', 'CHIP_AND_PIN'])[1 + g % 4],
       lpad((5411 + g % 60)::text, 4, '0'),
       'PERF MERCHANT ' || g,
       'CZ',
       CASE
           WHEN g = 1 THEN 'CLEARED'
           WHEN g % 9 = 0 THEN 'DECLINED'
           WHEN g % 7 = 0 THEN 'REVERSED'
           WHEN g % 5 = 0 THEN 'PARTIALLY_CLEARED'
           ELSE 'APPROVED'
       END,
       (ARRAY['GROCERIES', 'DINING', 'CASH', 'SHOPPING', 'TRANSPORT'])[1 + g % 5],
       CASE WHEN g % 9 = 0 THEN 'INSUFFICIENT_FUNDS' ELSE NULL END,
       CASE
           WHEN g = 1 THEN 1000 + g * 100
           WHEN g % 5 = 0 THEN (1000 + g * 100) / 2
           ELSE 0
       END,
       'PERF-NET-' || g,
       'perf-auth-' || g,
       NOW() - (g || ' hours')::interval,
       NOW() - (g || ' hours')::interval + interval '7 days',
       NOW() - (g || ' hours')::interval
FROM generate_series(1, 200) AS g;

-- One live dispute case against the fixed probe authorisation (g = 1), plus its evidence trail.
INSERT INTO card_dispute_cases (id, authorization_id, card_id, network_case_id, reason_code,
                                amount_minor_units, currency_code, status, scheme, scheme_status,
                                respond_by_date, evidence_reference, idempotency_key,
                                opened_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000d3'::uuid,
        '00000000-0000-0000-0000-0000000000d2'::uuid,
        '00000000-0000-0000-0000-0000000000d1'::uuid,
        'PERF-CASE-1', '4853',
        1100, 'CZK', 'EVIDENCE_SUBMITTED', 'MASTERCARD', 'NETWORK_EVIDENCE_RECEIVED',
        CURRENT_DATE + 20, 'evidence://perf/dispute-1',
        'perf-dispute-1',
        NOW() - interval '3 days', NOW() - interval '1 day');

INSERT INTO card_dispute_evidence (id, dispute_id, document_reference, note, scheme_status, submitted_at)
VALUES ('00000000-0000-0000-0000-0000000000d4'::uuid,
        '00000000-0000-0000-0000-0000000000d3'::uuid,
        'evidence://perf/dispute-1/receipt',
        'PERF evidence fixture', 'NETWORK_EVIDENCE_RECEIVED',
        NOW() - interval '1 day');

-- Three network-token mirror rows for the probe card, one per lifecycle state.
INSERT INTO card_network_tokens (id, card_id, token_reference, requestor_id, requestor_label,
                                 last4, status, scheme, expiry, idempotency_key,
                                 provisioned_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000d5'::uuid,
        '00000000-0000-0000-0000-0000000000d1'::uuid,
        'PERF-TOKEN-ACTIVE', 'perf-wallet-1', 'Perf Wallet', '0001', 'ACTIVE', 'MASTERCARD',
        CURRENT_DATE + 300, 'perf-token-1', NOW() - interval '30 days', NOW() - interval '2 days'),
       ('00000000-0000-0000-0000-0000000000d6'::uuid,
        '00000000-0000-0000-0000-0000000000d1'::uuid,
        'PERF-TOKEN-SUSPENDED', 'perf-wallet-1', 'Perf Wallet', '0002', 'SUSPENDED', 'MASTERCARD',
        CURRENT_DATE + 200, 'perf-token-2', NOW() - interval '20 days', NOW() - interval '1 day'),
       ('00000000-0000-0000-0000-0000000000d7'::uuid,
        '00000000-0000-0000-0000-0000000000d1'::uuid,
        'PERF-TOKEN-DELETED', 'perf-wallet-2', 'Perf Wallet 2', '0003', 'DELETED', 'VISA',
        NULL, 'perf-token-3', NOW() - interval '60 days', NOW() - interval '10 days');

COMMIT;
