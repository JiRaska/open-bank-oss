-- Read fixtures for the card-issuance read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The cards table starts empty (no Flyway seed), so this file adds rows:
--   * 200 cards for the fleet-wide fixture account (00000000-0000-0000-0000-000000000002,
--     PERF_ACCOUNT_ID in the k6 script) and a fixed fixture party — mostly ACTIVE, every 5th
--     PENDING and every 7th BLOCKED, spread over DEBIT/VIRTUAL/PHYSICAL card types and
--     VISA/MASTERCARD networks — so both the card list and the per-account listing page real
--     rows; and
--   * four card_category_rules rows for the fixed fixture card
--     00000000-0000-0000-0000-0000000000d1 (PERF_CARD_ID), so the category-limits read returns
--     a real rule set instead of an empty list.
--
-- ids are deterministic (hex of g, zero-padded) so a re-load collides on the PK and is a
-- no-op; the gate loads fixtures exactly once into a throwaway database.
BEGIN;

-- 200 cards for the fixture account/party. masked PANs stay masked (PCI DSS: the table stores
-- masked PANs only) and idempotency keys are unique per row. card_type stays inside the CardType
-- enum (DEBIT, CREDIT, PREPAID, VIRTUAL, SINGLE_USE — there is no PHYSICAL).
INSERT INTO cards (id, idempotency_key, party_id, account_id, product_code, card_type, network,
                   masked_pan, cardholder_name, embossed_name, expiry_date, status,
                   currency, activated_at, blocked_at, blocked_reason)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       'perf-card-' || g,
       '00000000-0000-0000-0000-000000000001'::uuid,
       '00000000-0000-0000-0000-000000000002'::uuid,
       CASE WHEN g % 3 = 0 THEN 'CARD_VIRTUAL' ELSE 'CARD_DEBIT' END,
       CASE WHEN g % 3 = 0 THEN 'VIRTUAL' WHEN g % 3 = 1 THEN 'DEBIT' ELSE 'CREDIT' END,
       CASE WHEN g % 2 = 0 THEN 'MASTERCARD' ELSE 'VISA' END,
       '************' || lpad((g % 10000)::text, 4, '0'),
       'PERF CARDHOLDER',
       'PERF/CARDHOLDER',
       CURRENT_DATE + 365,
       CASE
           WHEN g % 7 = 0 THEN 'BLOCKED'
           WHEN g % 5 = 0 THEN 'PENDING'
           ELSE 'ACTIVE'
       END,
       'CZK',
       CASE WHEN g % 7 = 0 OR g % 5 = 0 THEN NULL ELSE NOW() - (g || ' days')::interval END,
       CASE WHEN g % 7 = 0 THEN NOW() - (g || ' days')::interval ELSE NULL END,
       CASE WHEN g % 7 = 0 THEN 'PERF_BLOCK' ELSE NULL END
FROM generate_series(1, 200) AS g;

-- Category rules for the fixed detail-probe card: one block, two monthly caps, one uncapped.
-- Categories come from MerchantCategoryTaxonomy (the taxonomy the API serves) — ATM/FUEL are
-- NOT taxonomy ids; cash withdrawals are CASH.
INSERT INTO card_category_rules (card_id, category, blocked, monthly_limit_minor_units)
VALUES ('00000000-0000-0000-0000-0000000000d1'::uuid, 'GAMBLING', TRUE, NULL),
       ('00000000-0000-0000-0000-0000000000d1'::uuid, 'GROCERIES', FALSE, 5000000),
       ('00000000-0000-0000-0000-0000000000d1'::uuid, 'DINING', FALSE, NULL),
       ('00000000-0000-0000-0000-0000000000d1'::uuid, 'CASH', FALSE, 2000000);

COMMIT;
