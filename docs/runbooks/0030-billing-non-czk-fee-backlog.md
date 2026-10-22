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

All SQL below runs in `psql` on the billing database (`:'charge_keys'` and `:'reversal_keys'` are psql
variables).

## 1. Measure the backlog (read-only)

```sql
-- 1a. Outbox rows for non-CZK fee charges and reversals, by status and currency.
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

-- 1b. Unbooked non-CZK CHARGES, counted the same way from both sides. A charge is unbooked when its
-- fee has no journal. A fee whose REVERSAL died is also FAILED, but it has a journal, so it is not here.
SELECT 'outbox' AS side, o.payload::jsonb ->> 'currency' AS currency, count(*) AS charges
FROM billing_outbox o
WHERE o.event_type = 'billing.fee.post-intent.v1'
  AND o.status <> 'SENT'
  AND o.payload::jsonb ->> 'currency' <> 'CZK'
GROUP BY 1, 2
UNION ALL
SELECT 'fee', currency, count(*)
FROM assessed_fee
WHERE currency <> 'CZK' AND posting_status IN ('PENDING', 'FAILED') AND journal_id IS NULL
GROUP BY 1, 2
ORDER BY 2, 1;
```

In 1b the `outbox` and `fee` counts per currency should be equal: every unbooked charge has exactly
one outbox row that has not been sent. They can differ by the few rows the dispatcher is posting at
that moment (a fee turns `POSTED` just before its row turns `SENT`). Re-run 1b. A difference that
persists is its own defect: stop and investigate before replaying anything.

`PENDING`/`FAILED` outbox rows are still retrying; `DEAD` rows are terminal and need step 3.

## 2. Preconditions before any replay

1. The ledger serving the environment has applied V34: on the ledger database,
   `SELECT version, success FROM flyway_schema_history WHERE version = '34';` returns `success = t`,
   and `GET /api/v1/accounting-classes/resolve?accountingClass=FEE_INCOME_ACCOUNT&role=FEE_INCOME&currency=EUR`
   answers 200 with `glCode` 4004 (repeat for USD 4005, GBP 4006).
2. The billing release containing #13226 is deployed (non-CZK legs are sent by class).
3. Only rows whose `last_error` is the currency/mapping refusal (`does not match GL account currency`
   or `no GL account is mapped`) are replayed. Any other error is a different defect: those rows are
   left out, and the key lists below never contain them.

## 3. Replay (four-eyes, attributable, via the approved change process)

This is a data change on a money-path table. Do not run it from this runbook ad hoc.

**Four-eyes.**
1. The maker runs the read-only selections (A0, B0) and pastes their output into the change ticket.
   That output is the exact key list the change will touch, together with the step 1 output.
2. A second operator, the checker (never the maker), reviews the keys and the statements and approves.
3. The maker sets the approved lists as psql variables and runs each phase in one transaction. The
   maker checks that every `UPDATE`'s row count equals the number of approved keys. If it differs,
   the maker runs `ROLLBACK` and stops. Before and after step 1 output is attached to the ticket.

Every statement below is scoped to those approved keys. A row outside the list is never touched,
however it looks, and a fee deliberately left out cannot block a later gate.

`PENDING` and `FAILED` (retrying) outbox rows need nothing. Once step 2 holds, the dispatcher's next
tick posts them. Only `DEAD` rows are reset.

**Order matters: charges first, reversals only afterwards.** A reversal posts the compensating
journal of a charge. Replaying it before its charge is booked would credit back money that was
never debited. A dead reversal also marks its original fee `FAILED` (while its `journal_id` stays
set), so "charge booked" is judged by the fee's journal and its `SENT` outbox row, never by
`posting_status`.

### Phase A: charges (`billing.fee.post-intent.v1`)

```sql
-- A0 (read-only): the replayable DEAD charges. Its output is the ticket's charge key list.
SELECT f.idempotency_key, f.currency, f.charged_amount, o.last_error
FROM billing_outbox o
JOIN assessed_fee f ON f.idempotency_key = o.payload::jsonb ->> 'idempotencyKey'
WHERE o.event_type = 'billing.fee.post-intent.v1'
  AND o.status = 'DEAD'
  AND o.payload::jsonb ->> 'currency' IN ('EUR', 'USD', 'GBP')
  AND (o.last_error LIKE '%does not match GL account currency%'
       OR o.last_error LIKE '%no GL account is mapped%')
  AND f.posting_status = 'FAILED'
  AND f.journal_id IS NULL
ORDER BY f.idempotency_key;
```

The approved list is set as a psql array literal, e.g.
`\set charge_keys '{fee-2026-09-acc-1-f1-EUR,fee-2026-09-acc-2-f1-USD}'`.

```sql
BEGIN;
-- A1: those fees go back to PENDING. Row count must equal the number of approved charge keys.
UPDATE assessed_fee f
SET posting_status = 'PENDING', updated_at = now()
FROM billing_outbox o
WHERE f.idempotency_key = ANY (:'charge_keys'::text[])
  AND o.event_type = 'billing.fee.post-intent.v1'
  AND o.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
  AND o.status = 'DEAD'
  AND f.posting_status = 'FAILED'
  AND f.journal_id IS NULL;
-- A2: their outbox rows go back to PENDING. Same join, same predicates; row count must equal A1's.
UPDATE billing_outbox o
SET status = 'PENDING', attempt_count = 0, last_error = NULL, updated_at = now()
FROM assessed_fee f
WHERE f.idempotency_key = ANY (:'charge_keys'::text[])
  AND o.event_type = 'billing.fee.post-intent.v1'
  AND o.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
  AND o.status = 'DEAD'
  AND f.posting_status = 'PENDING'
  AND f.journal_id IS NULL;
COMMIT;
```

A2 runs after A1 in the same transaction, so it matches the fees A1 has just set to `PENDING`. Those
are exactly the approved keys whose fee was `FAILED` with no journal.

**Gate before phase B.** Wait for the dispatcher, then check the approved charges only:

```sql
-- Must return 0 rows: an approved charge that is not yet POSTED with a journal and a SENT row.
SELECT f.idempotency_key, f.posting_status, f.journal_id, o.status
FROM assessed_fee f
JOIN billing_outbox o ON o.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
                     AND o.event_type = 'billing.fee.post-intent.v1'
WHERE f.idempotency_key = ANY (:'charge_keys'::text[])
  AND (f.posting_status <> 'POSTED' OR f.journal_id IS NULL OR o.status <> 'SENT');
```

If any row comes back, stop and investigate. Do not start phase B.

### Phase B: reversals (`billing.fee.reversal-intent.v1`), only where the original charge is booked

```sql
-- B0 (read-only): the replayable DEAD reversals whose charge is booked. Output = reversal key list.
SELECT r.payload::jsonb ->> 'idempotencyKey' AS reversal_key,
       f.idempotency_key                     AS original_key,
       f.currency, f.charged_amount, r.last_error
FROM billing_outbox r
JOIN assessed_fee f   ON f.idempotency_key = r.payload::jsonb ->> 'originalIdempotencyKey'
JOIN billing_outbox c ON c.event_type = 'billing.fee.post-intent.v1'
                     AND c.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
WHERE r.event_type = 'billing.fee.reversal-intent.v1'
  AND r.status = 'DEAD'
  AND r.payload::jsonb ->> 'currency' IN ('EUR', 'USD', 'GBP')
  AND (r.last_error LIKE '%does not match GL account currency%'
       OR r.last_error LIKE '%no GL account is mapped%')
  AND c.status = 'SENT'
  AND f.journal_id IS NOT NULL
  AND f.reversal_journal_id IS NULL
ORDER BY 1;
```

The approved list holds the **reversal** keys, e.g.
`\set reversal_keys '{fee-reversal-2026-09-acc-3-f1-EUR}'`.

```sql
BEGIN;
-- B1: the original fee goes back to REVERSAL_PENDING. Row count must equal the approved reversal keys.
UPDATE assessed_fee f
SET posting_status = 'REVERSAL_PENDING', updated_at = now()
FROM billing_outbox r, billing_outbox c
WHERE r.payload::jsonb ->> 'idempotencyKey' = ANY (:'reversal_keys'::text[])
  AND r.event_type = 'billing.fee.reversal-intent.v1'
  AND r.status = 'DEAD'
  AND f.idempotency_key = r.payload::jsonb ->> 'originalIdempotencyKey'
  AND c.event_type = 'billing.fee.post-intent.v1'
  AND c.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
  AND c.status = 'SENT'
  AND f.journal_id IS NOT NULL
  AND f.reversal_journal_id IS NULL;
-- B2: those reversal rows go back to PENDING. Same joins and predicates; row count must equal B1's.
UPDATE billing_outbox r
SET status = 'PENDING', attempt_count = 0, last_error = NULL, updated_at = now()
FROM assessed_fee f, billing_outbox c
WHERE r.payload::jsonb ->> 'idempotencyKey' = ANY (:'reversal_keys'::text[])
  AND r.event_type = 'billing.fee.reversal-intent.v1'
  AND r.status = 'DEAD'
  AND f.idempotency_key = r.payload::jsonb ->> 'originalIdempotencyKey'
  AND c.event_type = 'billing.fee.post-intent.v1'
  AND c.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
  AND c.status = 'SENT'
  AND f.posting_status = 'REVERSAL_PENDING'
  AND f.journal_id IS NOT NULL
  AND f.reversal_journal_id IS NULL;
COMMIT;
```

A dead reversal whose charge is not booked is not in B0's output and stays `DEAD`. It goes into the
ticket for a business decision: it is either a write-off or a charge that must be replayed first.

### Afterwards

Re-run step 1. In 1b the replayed charges are gone from both sides. In 1a non-CZK `DEAD` rows are
zero, except the rows deliberately left out. Replayed charges show `POSTED` with a `journal_id`, and
replayed reversals show `REVERSED` with a `reversal_journal_id`. On the ledger, the net balance of
4004, 4005 and 4006 must equal the replayed charges minus the replayed reversals, per currency. The
journal POST is idempotent on the payload's `idempotencyKey`, so a row the ledger had already booked
replays as a no-op.

The general dead-letter procedure is [0026-billing-outbox-dead-letter.md](0026-billing-outbox-dead-letter.md).
FX revaluation of the 4004-4006 balances into CZK is out of scope here (ADR-0025/0046).
