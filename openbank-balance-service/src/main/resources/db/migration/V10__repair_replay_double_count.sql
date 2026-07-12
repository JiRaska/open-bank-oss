-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

-- V10: repair the booked-change replay double-count (issue #939).
--
-- WHAT: on 2026-07-12 the ledger's replay-booked-changes endpoint (#888, run for #860) re-emitted
-- the full 2026-05-01..2026-07-12 window. Movements booked BEFORE the ADR-0039 Phase D-2 cutover
-- (2026-06-17) had been applied by the payment saga's direct balance debit/credit, which wrote no
-- ledger_projection_event dedup row -- so the replay applied them a SECOND time: 28 accounts,
-- +2,700,500.00 CZK sub-ledger excess over the ledger (25x a doubled 100,000.00 initial deposit).
--
-- HOW: per-account correction back to the ledger-derived position (sum of credit-minus-debit over
-- deposit-control legs of POSTED+REVERSED entries -- the immutable-history semantics the trial
-- balance uses after the #939 ledger fix). The balance_movement insert doubles as the idempotency
-- marker: its PK (account_id, currency, reference_id, operation) makes a re-run insert nothing,
-- so the UPDATE (driven by the RETURNING set) touches nothing on a second application.
--
-- SCOPE: the account UUIDs exist only in the affected (sandbox) environment; anywhere else the
-- inserts reference no existing balance rows' accounts and the UPDATE joins to zero rows -- no-op.
-- (Precedent: ledger V10 hardcoded-id repair, 2026-07-02.)
--
-- VERIFICATION (post-deploy): POST /api/v1/balances/reconciliation must report difference 0.0000
-- and hasDrift=false for all currencies (requires the ledger trial-balance reversal fix, #939).
--
-- ROLLBACK: re-credit each account by its correction amount and delete the marker rows:
--   UPDATE balances b SET booked_amount = b.booked_amount + m.delta,
--                         available_amount = b.available_amount + m.delta,
--                         version = b.version + 1, updated_at = now()
--   FROM balance_movement m
--   WHERE m.reference_id = 'repair-939-replay-double-count'
--     AND b.account_id = m.account_id AND b.currency = m.currency;
--   DELETE FROM balance_movement WHERE reference_id = 'repair-939-replay-double-count';

WITH correction (account_id, currency, reference_id, operation, delta) AS (
    VALUES
        ('032c1003-4f4f-41e0-ad26-2091528e73ef'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('0d8066e6-0913-4da4-8693-e7aa2e584dd0'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('161a7c96-7d2a-40cb-836c-5521487a0a92'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('1f898af4-089f-4b96-9442-8c715eb5b761'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('25a81460-93a0-439c-bada-505a55e37c3d'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 26667.0000),
        ('27d0328a-fd58-4105-b92f-b8f0cd324a1b'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('44785602-8d1f-4a07-b7a9-ec1579961802'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('4d03fcce-ed14-4a0e-8cd1-62faf4c7117a'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 73333.0000),
        ('62d511dc-9699-4489-abcc-a3c985eb33a4'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('6c2c9c26-5b26-422d-b618-6c407f548d0d'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100500.0000),
        ('7609fd7d-f331-47dd-a655-40233632908a'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('7f6128fe-0156-4668-a571-52c758595859'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('87b2df3a-c069-40c9-bb4b-81eee535dbef'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('87d9caea-abbc-48d9-b544-bcf331a024c1'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('8b721863-841b-4708-98ad-5c4ef41ccc56'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('8cc03488-2d1d-49ca-a4ea-0c14d3b81886'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('8ebb86e8-4eac-4262-ae35-f00af0aabc4c'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('92717ebc-7016-4eb1-8039-da1a7923c33d'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('99b9944d-ddfc-4e16-973c-26feb5fe7579'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('a1e58eb1-bf49-4e11-8064-c02d32dd62a6'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('aa45a66f-188d-407c-9bcd-782081a9a1ca'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('b19459ee-5070-4b20-bd55-efd7b9ab3c0c'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('dd25ce64-262d-43d6-8aaf-991dbb775214'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('e3b64382-1dd4-4e67-b512-4f4b145348f8'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('e67996ae-7b3f-46be-b6eb-49246cf9e538'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('e898c494-de9e-4a53-97ac-2564c138dbb5'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('e98dbff5-87eb-4c70-89fd-df9c4398a307'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000),
        ('fe781dfb-3063-461e-8f4a-d01fba71e2bf'::uuid, 'CZK', 'repair-939-replay-double-count', 'DEBIT', 100000.0000)
),
marker AS (
    INSERT INTO balance_movement (account_id, currency, reference_id, operation, delta)
    SELECT account_id, currency, reference_id, operation, delta FROM correction
    ON CONFLICT DO NOTHING
    RETURNING account_id, currency, delta
)
UPDATE balances b
SET booked_amount    = b.booked_amount    - m.delta,
    available_amount = b.available_amount - m.delta,
    version          = b.version + 1,
    updated_at       = now()
FROM marker m
WHERE b.account_id = m.account_id
  AND b.currency   = m.currency;
