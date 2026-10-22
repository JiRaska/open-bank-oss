# Billing: non-CZK fee backlog after the per-currency fee-income accounts

Issue #13226. Before ledger migration `V34__per_currency_fee_income_accounts.sql`, the chart had
one fee-income account (4003, CZK) and the ledger refuses a journal line whose currency differs
from its GL account's. Every EUR/USD/GBP fee charge therefore answered 4xx at the ledger and its
`billing_outbox` row retried until it went `DEAD` (fee `posting_status = FAILED`). V34 adds
4004 EUR, 4005 USD and 4006 GBP and maps `FEE_INCOME_ACCOUNT` to them; billing now posts every
non-CZK fee by accounting class, even with `openbank.billing.ledger.post-by-class` off. Income is
booked in the fee's own currency; nothing is converted.

A fee in any other currency (e.g. CHF) is not written to the outbox at all: it is a counted
assessment skip, `openbank_billing_assessment_skipped_total{reason="FEE_INCOME_CURRENCY_UNMAPPED"}`.

## 1. Measure the backlog (read-only, billing database)

```sql
-- Outbox rows for non-CZK fee charges and reversals, by status and currency.
SELECT o.status,
       o.event_type,
       o.payload::jsonb ->> 'currency' AS currency,
       count(*)                        AS rows,
       sum((o.payload::jsonb ->> 'amount')::numeric) AS amount,
       min(o.created_at)               AS oldest,
       max(o.attempt_count)            AS max_attempts
FROM billing_outbox o
WHERE o.event_type IN ('billing.fee.post-intent.v1', 'billing.fee.reversal-intent.v1')
  AND o.payload::jsonb ->> 'currency' <> 'CZK'
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

-- The same backlog seen from the fee side: assessed but never booked.
SELECT currency, posting_status, count(*) AS fees, sum(charged_amount) AS amount
FROM assessed_fee
WHERE currency <> 'CZK' AND posting_status IN ('PENDING', 'FAILED')
GROUP BY 1, 2
ORDER BY 1, 2;
```

`PENDING` rows with a growing `attempt_count` are still retrying; `DEAD` rows are terminal and need
step 3. The two queries must agree in count per currency (one outbox row per chargeable fee); a
mismatch is its own defect, stop and investigate before replaying anything.

## 2. Preconditions before any replay

1. The ledger serving the environment has applied V34: on the ledger database,
   `SELECT version, success FROM flyway_schema_history WHERE version = '34';` returns `success = t`,
   and `GET /api/v1/accounting-classes/resolve?accountingClass=FEE_INCOME_ACCOUNT&role=FEE_INCOME&currency=EUR`
   answers 200 with `glCode` 4004 (repeat for USD 4005, GBP 4006).
2. The billing release containing #13226 is deployed (non-CZK legs are sent by class).
3. Every row's `last_error` is the currency/mapping refusal (`does not match GL account currency`
   or `no GL account is mapped`). Any other error is a different defect; leave those rows out.

## 3. Replay (four-eyes, attributable, via the approved change process)

This is a data change on a money-path table. Do not run it from this runbook ad hoc.

**Four-eyes.** A maker prepares the change ticket with the step 1 output and the exact statements
below, with the currency list and the `last_error` patterns filled in. A second operator (the
checker, never the maker) reviews and approves it. The maker runs it only after approval, inside
one transaction per phase, and checks that the row counts printed by each `UPDATE` match the counts
in the ticket. If they differ, run `ROLLBACK` and stop. Attach the before and after step 1 output to
the ticket.

`PENDING` and `FAILED` (retrying) outbox rows need nothing. Once 2.1 and 2.2 hold, the dispatcher's
next tick posts them. Only `DEAD` rows are reset.

**Order matters: charges first, reversals only afterwards.** A reversal posts the compensating
journal of a charge. Replaying it before its charge is booked would credit back money that was
never debited. A dead reversal also marks its original fee `FAILED`. So "original charge booked"
is judged by the charge's journal and its `SENT` outbox row, never by `posting_status`.

### Phase A: charges (`billing.fee.post-intent.v1`)

```sql
BEGIN;
-- A1: fees whose charge row is DEAD go back to PENDING (expected count = step 1 DEAD charges).
UPDATE assessed_fee f
SET posting_status = 'PENDING', updated_at = now()
FROM billing_outbox o
WHERE o.event_type = 'billing.fee.post-intent.v1'
  AND o.status = 'DEAD'
  AND o.payload::jsonb ->> 'currency' IN ('EUR', 'USD', 'GBP')
  AND (o.last_error LIKE '%does not match GL account currency%'
       OR o.last_error LIKE '%no GL account is mapped%')
  AND f.idempotency_key = o.payload::jsonb ->> 'idempotencyKey'
  AND f.posting_status = 'FAILED'
  AND f.journal_id IS NULL;
-- A2: the same rows' outbox entries go back to PENDING (expected count = A1's count).
UPDATE billing_outbox o
SET status = 'PENDING', attempt_count = 0, last_error = NULL, updated_at = now()
WHERE o.event_type = 'billing.fee.post-intent.v1'
  AND o.status = 'DEAD'
  AND o.payload::jsonb ->> 'currency' IN ('EUR', 'USD', 'GBP')
  AND (o.last_error LIKE '%does not match GL account currency%'
       OR o.last_error LIKE '%no GL account is mapped%');
COMMIT;
```

**Gate before phase B.** Wait for the dispatcher, then confirm every charge reset in phase A is booked:

```sql
-- Must return 0 rows: a replayed non-CZK charge that is not yet POSTED with a journal.
SELECT f.idempotency_key, f.posting_status, o.status
FROM assessed_fee f
JOIN billing_outbox o ON o.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
                     AND o.event_type = 'billing.fee.post-intent.v1'
WHERE f.currency IN ('EUR', 'USD', 'GBP')
  AND (f.posting_status <> 'POSTED' OR f.journal_id IS NULL OR o.status <> 'SENT')
  AND f.posting_status NOT IN ('REVERSAL_PENDING', 'REVERSED');
```

If any row comes back, stop and investigate. Do not start phase B.

### Phase B: reversals (`billing.fee.reversal-intent.v1`), only where the original charge is booked

```sql
BEGIN;
-- B1: the original fee goes back to REVERSAL_PENDING (expected count = eligible DEAD reversals).
UPDATE assessed_fee f
SET posting_status = 'REVERSAL_PENDING', updated_at = now()
FROM billing_outbox r
JOIN billing_outbox c ON c.event_type = 'billing.fee.post-intent.v1'
                     AND c.status = 'SENT'
                     AND c.payload::jsonb ->> 'idempotencyKey' = r.payload::jsonb ->> 'originalIdempotencyKey'
WHERE r.event_type = 'billing.fee.reversal-intent.v1'
  AND r.status = 'DEAD'
  AND r.payload::jsonb ->> 'currency' IN ('EUR', 'USD', 'GBP')
  AND (r.last_error LIKE '%does not match GL account currency%'
       OR r.last_error LIKE '%no GL account is mapped%')
  AND f.idempotency_key = r.payload::jsonb ->> 'originalIdempotencyKey'
  AND f.journal_id IS NOT NULL
  AND f.reversal_journal_id IS NULL;
-- B2: only those reversal rows go back to PENDING (expected count = B1's count).
UPDATE billing_outbox r
SET status = 'PENDING', attempt_count = 0, last_error = NULL, updated_at = now()
FROM billing_outbox c, assessed_fee f
WHERE r.event_type = 'billing.fee.reversal-intent.v1'
  AND r.status = 'DEAD'
  AND r.payload::jsonb ->> 'currency' IN ('EUR', 'USD', 'GBP')
  AND (r.last_error LIKE '%does not match GL account currency%'
       OR r.last_error LIKE '%no GL account is mapped%')
  AND c.event_type = 'billing.fee.post-intent.v1'
  AND c.status = 'SENT'
  AND c.payload::jsonb ->> 'idempotencyKey' = r.payload::jsonb ->> 'originalIdempotencyKey'
  AND f.idempotency_key = r.payload::jsonb ->> 'originalIdempotencyKey'
  AND f.journal_id IS NOT NULL
  AND f.reversal_journal_id IS NULL;
COMMIT;
```

A dead reversal whose charge is not booked stays `DEAD`. It is listed in the ticket for a business
decision: it is either a write-off or a charge that must be replayed first.

### Afterwards

Re-run the step 1 queries. Non-CZK `DEAD` rows must be zero, except reversals deliberately left in
phase B. Replayed charges must show `POSTED` with a `journal_id`, and replayed reversals `REVERSED`
with a `reversal_journal_id`. On the ledger, the net balance of 4004, 4005 and 4006 must equal the
replayed charges minus the replayed reversals, per currency. The journal POST is idempotent on the
payload's `idempotencyKey`, so a row the ledger had somehow already booked replays as a no-op.

The general dead-letter procedure is [0026-billing-outbox-dead-letter.md](0026-billing-outbox-dead-letter.md).
FX revaluation of the 4004-4006 balances into CZK is out of scope here (ADR-0025/0046).
