# Billing: a fee refused into a closed accounting day

Alert `BillingFeeRefusedIntoClosedDay` (#13167, ADR-0342 D4). The ledger answered a fee charge with
`409 ACCOUNTING_DAY_CLOSED`: the accounting day the journal was dated for no longer accepts postings
(it is `TIED_OUT`/`LOCKED`, or its period is frozen). Retrying cannot succeed, so
`LedgerOutboxEventPublisher` marked the fee `FAILED` immediately and completed its outbox row.

What that means:
- **The customer was not charged.** Nothing re-posts the fee on its own.
- **The cycle's day is REFUSED to the ledger.** `BillingCloseParticipationScheduler` counts the
  cycle's `FAILED` fees and reports the day REFUSED, which holds the ledger's close in `CUTOFF` under
  `enforce` (in `shadow`, it counts `would_hold`).

The recovery is to **re-bill the fee into the next open day, under a NEW idempotency key that is
recorded, with four-eyes approval**. Never re-open the closed day to fit the fee in, and never reuse
the original key.

All SQL runs in `psql` on the billing database.

## 1. Measure (read-only)

```sql
-- 1a. Fees refused into a closed day: FAILED, no journal, and their outbox row completed SENT. That is
-- the signature of the closed-day path. A DEAD row is exhausted retries instead: see runbook 0026.
SELECT f.idempotency_key, f.cycle_id, f.currency, f.charged_amount, o.sent_at
FROM assessed_fee f
JOIN billing_outbox o ON o.payload::jsonb ->> 'idempotencyKey' = f.idempotency_key
WHERE f.posting_status = 'FAILED'
  AND f.journal_id IS NULL
  AND o.event_type = 'billing.fee.post-intent.v1'
  AND o.status = 'SENT'
  AND NOT EXISTS (SELECT 1 FROM billing_fee_rebill r WHERE r.original_key = f.idempotency_key)
ORDER BY f.cycle_id, f.idempotency_key;
```

Confirm the refusal in the logs (`fee posting <key> refused by the ledger: ACCOUNTING_DAY_CLOSED`) for
every key you intend to re-bill. A key that is not in the logs did not take this path. Stop and
investigate it.

## 2. Preconditions

1. The ledger's current accounting day is `OPEN`. The re-bill posts with today's date, so it lands in
   the open day. It never lands in the closed one.
2. Finance accepts that the fee is booked in a later day than its cycle. The re-bill is the
   statement's late-charge line, not a correction of the closed day.

## 3. Re-bill (four-eyes, attributable)

This is a data change on a money-path table. Run it under an approved change ticket.

1. The **maker** pastes the step 1a output (the key list) into the ticket.
2. A different person, the **checker**, approves it. `billing_fee_rebill` enforces `maker <> checker`,
   so the same person cannot be both.
3. The maker runs, in ONE transaction, for exactly the approved keys:

```sql
BEGIN;
-- \set keys '{fee-2026-10-acc-1-f1-CZK}'   \set ticket 'CHG-1234'   \set maker 'a.maker'   \set checker 'b.checker'
INSERT INTO billing_fee_rebill (rebill_key, original_key, reason, change_ticket, maker, checker)
SELECT f.idempotency_key || ':rebill:' || to_char(now() AT TIME ZONE 'Europe/Prague', 'YYYY-MM-DD'),
       f.idempotency_key, 'ACCOUNTING_DAY_CLOSED', :'ticket', :'maker', :'checker'
FROM assessed_fee f
WHERE f.idempotency_key = ANY (:'keys'::text[]) AND f.posting_status = 'FAILED' AND f.journal_id IS NULL;

-- One new outbox row per re-bill. Its payload is the original's, with the NEW key and `rebillOf`
-- naming the original fee, so the dispatcher posts a fresh journal and settles the ORIGINAL fee row.
INSERT INTO billing_outbox (event_id, aggregate_id, event_type, payload, status)
SELECT gen_random_uuid(), o.aggregate_id, o.event_type,
       jsonb_set(jsonb_set(o.payload::jsonb, '{idempotencyKey}', to_jsonb(r.rebill_key)),
                 '{rebillOf}', to_jsonb(r.original_key))::text,
       'PENDING'
FROM billing_fee_rebill r
JOIN billing_outbox o ON o.payload::jsonb ->> 'idempotencyKey' = r.original_key
                     AND o.event_type = 'billing.fee.post-intent.v1'
WHERE r.original_key = ANY (:'keys'::text[]) AND r.change_ticket = :'ticket';

UPDATE assessed_fee SET posting_status = 'PENDING', updated_at = now()
WHERE idempotency_key = ANY (:'keys'::text[]) AND posting_status = 'FAILED' AND journal_id IS NULL;
-- Every row count must equal the number of approved keys. If any differs: ROLLBACK and stop.
COMMIT;
```

## 4. Verify

- Within one dispatcher tick, each fee is `POSTED`, and its `journal_id` is the journal booked under
  the re-bill key. Check with
  `SELECT idempotency_key, posting_status, journal_id FROM assessed_fee WHERE idempotency_key = ANY (:'keys'::text[]);`.
- While those fees were `PENDING`, the reporter did not report the cycle's day. Once they are
  `POSTED`, its next tick sends the day again with no FAILED fees.
  - If the day is still in the 3-day lookback: the new report is RAN. A day the ledger already closed
    stores it `late` and does not move it.
  - If the day is older than the lookback: record an operator waiver on the ledger
    (`PUT …/participants/billing/waiver`) that cites the change ticket.
- If the alert fires again for a re-bill key, the open day closed in between. Repeat this runbook for
  the new key and do not retry the same row.

## Rollback

Until the dispatcher posts it, a re-bill can be withdrawn. Delete its `PENDING` outbox row, set the fee
back to `FAILED` and delete the `billing_fee_rebill` row, all in one transaction under the same ticket.
Once it has POSTED, it is a booked charge, and it is undone only through the normal four-eyes reversal
(`POST /api/v1/fees/reverse`).
