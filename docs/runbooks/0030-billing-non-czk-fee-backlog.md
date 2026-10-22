# Billing: non-CZK fee backlog after the per-currency fee-income accounts

Issue #13226. Before ledger migration `V35__per_currency_fee_income_accounts.sql`, the chart had
one fee-income account (4003, CZK) and the ledger refuses a journal line whose currency differs
from its GL account's. Every EUR/USD/GBP fee charge therefore answered 4xx at the ledger and its
`billing_outbox` row retried until it went `DEAD` (fee `posting_status = FAILED`). V35 adds
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

1. The ledger serving the environment has applied V35: on the ledger database,
   `SELECT version, success FROM flyway_schema_history WHERE version = '35';` returns `success = t`,
   and `GET /api/v1/accounting-classes/resolve?accountingClass=FEE_INCOME_ACCOUNT&role=FEE_INCOME&currency=EUR`
   answers 200 with `glCode` 4004 (repeat for USD 4005, GBP 4006).
2. The billing release containing #13226 is deployed (non-CZK legs are sent by class).
3. Every row's `last_error` is the currency/mapping refusal (`does not match GL account currency`
   or `no GL account is mapped`). Any other error is a different defect; leave those rows out.

## 3. Replay (four-eyes, attributable, via the approved change process)

This is a data change on a money-path table. Do not run it from this runbook ad hoc.

- `PENDING` rows need nothing: the dispatcher's next tick posts them once 2.1 and 2.2 hold.
- `DEAD` rows, restricted to the rows classified in 2.3, are set back to `PENDING` with
  `attempt_count = 0` and `last_error = NULL`; their fees go back from `FAILED` to `PENDING` in the
  same change. The journal POST is idempotent on the payload's `idempotencyKey`, so a row the ledger
  somehow already booked replays as a no-op.
- Re-run the step 1 queries afterwards: the non-CZK `DEAD`/`FAILED` counts must reach zero and the
  fees must show `POSTED` with a `journal_id`. On the ledger, the balances of 4004-4006 must equal the
  replayed amounts per currency.

The general dead-letter procedure is [0026-billing-outbox-dead-letter.md](0026-billing-outbox-dead-letter.md).
FX revaluation of the 4004-4006 balances into CZK is out of scope here (ADR-0025/0046).
