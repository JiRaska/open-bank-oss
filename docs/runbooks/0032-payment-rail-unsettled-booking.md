# Payment rails: unsettled booking (mismatch, FAILED replay, 409, unexpected status)

Issue #13623. The rails count a customer leg as booked in transaction-service only on HTTP 201 +
`COMPLETED` + a full match to the request. Every other answer leaves the payment holding with its
durable booking clock open, and transaction-service replays the stored answer for a key, so a
deterministic answer comes back the same on every retry. These alerts name that state:

| Alert | Rail label | Held in | Key | SLA |
|---|---|---|---|---|
| `SepaPaymentBookingUnsettled` | `SEPA` | `PROCESSING` | `sepa-settlement-<paymentId>` | 30m |
| `SepaPaymentRefundUnsettled` | `SEPA` (`operation="refund"`) | `PROCESSING`/`COMPLETED` + open return intent | `sepa-reversal-<paymentId>` | 1h |
| `DomesticPaymentBookingUnsettled` | `DOMESTIC_CZK` | `SENT_TO_CLEARING` | `domestic-settlement-<paymentId>` | 1h |
| `SwiftBookingUnsettled` | `SWIFT` | `SENT` | `swift-settlement-<messageId>` | 30m |

The rule file is `openbank-infra/gitops/components/payments/prometheus-rules-unsettled.yaml`; why
each SLA is what it is sits next to each rule. Three rules hold for every section below:

- **Never rotate a key and never use a POST as a lookup.** A new key books the money a second time.
- **A 409 or an uncertain answer is not proof that nothing was booked.** Read the row.
- **Only a re-drive closes the clock in-band.** It runs the normal code path under the same key, so the
  payment completes only on a strictly matched answer. A correction made outside it (a four-eyes
  reversal, a ledger adjustment) does not close the clock; see "Closing a corrected payment" below.

## 0. Which payment, and which answer did it get

```promql
# Which outcome is accumulating (attempts, not unique payments):
sum by (rail, outcome) (increase(openbank_payment_unsettled_total{rail=~"SEPA|DOMESTIC_CZK|SWIFT"}[1h]))
```

Find the payments behind the age (read-only). SEPA, on `sepa-payment-db`:

```bash
kubectl cnpg psql sepa-payment-db -n payments -- -d openbank_sepa_payment
```

```sql
-- Outbound bookings still open, oldest first.
SELECT payment_id, status, payment_type, amount, currency, booking_first_due_at,
       now() - booking_first_due_at AS waiting, transaction_id
FROM sepa_payments
WHERE booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
ORDER BY booking_first_due_at
LIMIT 20;

-- Returns whose reversal is unproven, oldest first.
SELECT payment_id, status, return_first_received_at, now() - return_first_received_at AS waiting,
       return_unresolved_cause, return_original_transaction_id, return_reason_code
FROM sepa_payments
WHERE return_first_received_at IS NOT NULL AND return_resolved_at IS NULL
ORDER BY return_first_received_at
LIMIT 20;
```

Domestic, on `domestic-payment-db`:

```bash
kubectl cnpg psql domestic-payment-db -n payments -- -d openbank_domestic_payment
```

```sql
SELECT payment_id, status, amount, currency, booking_first_due_at,
       now() - booking_first_due_at AS waiting
FROM domestic_payments
WHERE booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
ORDER BY booking_first_due_at
LIMIT 20;
```

SWIFT, on `swift-service-db`:

```bash
kubectl cnpg psql swift-service-db -n payments -- -d openbank_swift
```

```sql
-- amount_minor_units is in the currency's minor units (12.34 EUR = 1234).
SELECT id AS message_id, status, message_type, currency, amount_minor_units, value_date,
       ordering_customer_account_id, booking_first_due_at, now() - booking_first_due_at AS waiting
FROM swift_messages
WHERE message_type = 'MT103' AND booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
ORDER BY booking_first_due_at
LIMIT 20;
```

The gauge reads the same set (MT103, any status), so a message an operator acknowledged or rejected
still counts. A SWIFT row with `ordering_customer_account_id IS NULL` never called transaction-service, and the
counter does not count it. Neither does a stored value date that no longer parses. Both hold the clock
open, so the age alert is the only thing that sees them. Step 1 then finds no row.

## 1. Query transaction-service by key

transaction-service has no REST lookup by idempotency key, so read its database. Read-only:

```bash
kubectl cnpg psql transaction-db -n payments -- -d openbank_transactions
```

```sql
\set key 'sepa-settlement-<paymentId>'   -- or sepa-reversal-<paymentId> / domestic-settlement-<paymentId> / swift-settlement-<messageId>
SELECT t.id, t.status, t.type, t.source_account_id, t.target_account_id, t.amount, t.currency_code,
       t.rail, t.instruction_type, t.value_date, t.booking_date, t.completed_at, t.failure_reason
FROM transaction_idempotency_keys k
JOIN transactions t ON t.id = k.transaction_id AND t.booking_date = k.booking_date
WHERE k.idempotency_key = :'key';
```

Compare the row with the payment row from step 0. The rail sends:

| Rail | type | source | target | rail | instructionType |
|---|---|---|---|---|---|
| sepa-payment | `TRANSFER` | `debtor_account_id` | none | `SEPA_CT` (SCT) / `SEPA_INST` (SCT_INST) | `ONE_OFF` |
| domestic-payment | `DEBIT` | `debtor_account_id` | payee account for an in-house payee, else none | `DOMESTIC` | `ONE_OFF` |
| swift-service | `DEBIT` | `ordering_customer_account_id` | none | `SWIFT` | `ONE_OFF` |

Amount and currency must equal the payment's exactly (for SWIFT, `amount_minor_units` divided by the
currency's minor-unit factor); `value_date` may legitimately be later.

## 2. Decide

| What step 1 returned | Meaning | Remedy |
|---|---|---|
| No row | The call never landed (transport error, timeout). | **Re-drive** (section 3). |
| `COMPLETED`, every field matches | Booked; the answer was lost or arrived after the adapter gave up. | **Re-drive**: the replay is a matched 201 and closes the clock. |
| `PENDING` / `PROCESSING` | transaction-service has not finished. | Wait for it; re-drive once it is `COMPLETED`. |
| `COMPLETED`, money matches but `rail` / `instruction_type` differ | A booking made before strict matching (historical `SEPA_CT` for SCT_INST, null instruction type). The money is right. | **Reconcile**: record it as booked (section 4). Do not reverse. |
| `COMPLETED`, account, amount or currency differ | The key carries a different booking. The ledger is wrong. | **Reverse** that transaction (section 4), then reconcile. Never rebook under a new key without finance sign-off. |
| `FAILED` | A definitive refusal (blocked account, cover). It is replayed forever. | **Reconcile** with finance: the money has left via the scheme, so the debit has to be booked by a correction. Retrying achieves nothing. |
| `REVERSED` | Someone already reversed it. | Find who and why before any further action. |

## 3. Re-drive

**A re-drive is pointless until step 2 has dealt with a stored `FAILED` or mismatched answer.** The
re-drive sends the same key, and transaction-service answers a known key from the stored row without
reading the body. A key that holds `FAILED`, or a `COMPLETED` row that does not match, comes back the
same on every re-drive, and the clock stays open. Re-drive only for "No row", for a matching
`COMPLETED` row, or for a `PENDING`/`PROCESSING` row that has since completed. For `FAILED` and
mismatch rows, step 4 is the remedy, not a re-drive.

**A Temporal retry is also a re-drive.** `SubmitToScheme` re-enters for a held payment with an open
clock, so if the activity is retried for any reason (a worker restart or timeout after the PROCESSING
write: up to 3 attempts within 10 minutes), settlement is called again on its own, under the same
key, with the value date pinned to the acceptance date. Expect `openbank_payment_unsettled_total` to
count those attempts as well.

### SEPA payment outbound booking

Nothing retries a held SEPA booking on its own. The re-drive is a Temporal reset of the payment's
workflow to just before `SubmitToScheme`. That activity re-enters for a `PROCESSING` payment with an
open booking clock and calls settlement again under the same key (it never resubmits to the scheme,
and a `PROCESSING` row with no tracked clock is never touched).

```bash
ID=<paymentId>
T="kubectl -n temporal exec deploy/temporal-admintools -- temporal --address temporal-frontend.temporal.svc.cluster.local:7233 --namespace openbank-payments"
# The WorkflowTaskCompleted event that scheduled SubmitToScheme:
EVENT=$($T workflow show --workflow-id "sepa-payment-$ID" --output json \
  | jq -r '.events as $e | range(1; $e|length) as $i
      | select(($e[$i].eventType|tostring|test("ACTIVITY_TASK_SCHEDULED|ActivityTaskScheduled"))
               and $e[$i].activityTaskScheduledEventAttributes.activityType.name == "SubmitToScheme")
      | $e[$i-1].eventId')
echo "reset to event $EVENT"   # must be one number; stop if it is empty
$T workflow reset --workflow-id "sepa-payment-$ID" --event-id "$EVENT" --reason "#13623 re-drive settlement"
```

Then confirm in `sepa-payment-db` that `booking_proven_at` is set and the status is `COMPLETED`. If it
is still `PROCESSING`, `openbank_payment_unsettled_total` grew again and step 2 applies.

### SEPA payment refund (reversal)

A return whose reversal was unproven (409, 202, timeout, no id) stays out of `RETURNED`, keeps its
return intent, and answered the sender 503. Re-drive it by submitting the **same** pacs.004 again
(same `OrgnlEndToEndId`). The stored intent, original transaction id and `sepa-reversal-<paymentId>`
key are reused; a resubmission never replaces them.

```bash
kubectl -n payments port-forward svc/sepa-payment-service 8115:8115 &
curl -sS -X POST http://localhost:8115/api/v1/sepa-payments/returns \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' \
  --data-binary @original-pacs004.xml
```

`$TOKEN` is a `ROLE_API`/`ROLE_ADMIN` token. 200 with `RETURNED` closes it. 503 means it is still
unproven. Go back to step 1 with the reversal key. `return_unresolved_cause =
UNRESOLVED_ORIGINAL_TRANSACTION` means the outbound booking itself was never proven: settle that first
(the section above), then resubmit the return.

### Domestic payment outbound booking

While the workflow is running, Temporal is already retrying every 5 minutes under the same key. Look
at what it gets back:

```bash
$T workflow describe --workflow-id "domestic-payment-$ID"   # pending SettlePayment: attempt + last failure
```

Fix the cause (step 2), and the next attempt completes. Once the workflow has **failed** (7-day
schedule-to-close), reset it to just before `SettlePayment`. That activity is re-entrant for
`SENT_TO_CLEARING` and does not resubmit to clearing:

```bash
EVENT=$($T workflow show --workflow-id "domestic-payment-$ID" --output json \
  | jq -r '.events as $e | range(1; $e|length) as $i
      | select(($e[$i].eventType|tostring|test("ACTIVITY_TASK_SCHEDULED|ActivityTaskScheduled"))
               and $e[$i].activityTaskScheduledEventAttributes.activityType.name == "SettlePayment")
      | $e[$i-1].eventId' | tail -1)
$T workflow reset --workflow-id "domestic-payment-$ID" --event-id "$EVENT" --reason "#13623 re-drive settlement"
```

### SWIFT outbound booking

**swift-service re-drives a held booking on its own (#13686).** It books once, right after scheme
acceptance; if that is unproven, `SwiftSettlementRedriveScheduler` (every 60 s) calls transaction-service
again for each MT103 that is `SENT` with an open clock and at least 2 minutes old. Same key, request
rebuilt from the stored message (the value date is the MT103's 32A date, never today's), and it never
resubmits to the scheme. Each message is claimed with a token and a 2-minute lease by one conditional
UPDATE, so two pods or an overlapping tick make one call, not two. An unproven attempt backs off
`min(2m * 2^(n-1), 1h)`; after 10 attempts (about 6h) it is **exhausted**: no longer retried,
`openbank_payment_unsettled_total{rail="SWIFT",outcome="redrive-exhausted"}` increments, and the clock
stays open, so this alert keeps firing. A transport blip is normally booked before the alert's 30m.

**The alert can fire while the re-drive is still live.** 10 attempts span about 6 hours and the alert
fires at 30 minutes, so for most of that window the sweep is still calling transaction-service under
`swift-settlement-<messageId>`. Booking or reversing by hand, or under any other key, while it is live
races the sweep and can debit the customer twice. So **step 1 for SWIFT is always the stop below**,
before step 1 of the shared procedure and before any manual action.

**Step S1 — stop the re-drive for this message (four-eyes, before anything else).** Open a ticket
recording the message id, why it is stopped, the approver and the executor (two different people). The
approver reviews the statement and the ticket; the executor runs it and pastes the result into the
ticket. It refuses a message whose re-drive holds a live claim: `UPDATE 0` means a call is in flight.
Wait for the lease to pass (2 minutes) and run it again. Never act by hand on `UPDATE 0`.

```sql
-- swift-service-db, as the application owner. Expect UPDATE 1.
UPDATE swift_messages
SET booking_redrive_stopped_at = now(),
    booking_redrive_stop_reason = '<ticket> approver=<name> executor=<name>'
WHERE id = '<messageId>' AND booking_redrive_stopped_at IS NULL
  AND (booking_redrive_claimed_until IS NULL OR booking_redrive_claimed_until < now());

-- Then confirm: stopped, no live claim. Only now go on to the shared steps.
SELECT booking_redrive_stopped_at, booking_redrive_stop_reason, booking_redrive_claim,
       booking_redrive_claimed_until, now() AS checked_at
FROM swift_messages WHERE id = '<messageId>';
```

A stopped message is never claimed by the sweep again. Its booking clock stays open, so the alert keeps
firing until the message is closed as in "Closing a corrected payment".

What the re-drive does **not** cover:

- a message an operator already acknowledged or rejected (it is no longer `SENT`). `ack`/`reject` also
  stop its re-drive, and answer 409 while a re-drive holds a live claim: retry after the 2-minute lease;
- a stored `FAILED` or mismatched answer, which comes back the same on every attempt and exhausts;
- a message with no `ordering_customer_account_id` or an unparseable value date, which never calls
  transaction-service and exhausts too.

Where the re-drive stands, on `swift-service-db` (read-only):

```sql
SELECT id AS message_id, status, booking_redrive_attempts, booking_redrive_next_at,
       booking_redrive_claimed_until, booking_redrive_exhausted_at, booking_redrive_stopped_at
FROM swift_messages
WHERE message_type = 'MT103' AND booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
ORDER BY booking_first_due_at
LIMIT 20;
```

If the sweep itself is down, `WorkflowLivenessStale{workflow="swift-settlement-redrive"}` fires; it is
switched by `SWIFT_SETTLEMENT_REDRIVE_ENABLED` (default `true`).

Then the remedy depends on step 1:

| Step 1 for `swift-settlement-<messageId>` | Remedy |
|---|---|
| No row, re-drive still counting | Wait: the next attempt is at `booking_redrive_next_at`. If transaction-service is down, fix that first. |
| No row, exhausted (the cause was an outage, now fixed) | **Re-drive** with the command below. |
| No row, no `ordering_customer_account_id` / unparseable value date | Nothing can be sent in-band. Escalate to finance: the MT103 has left, so the debit is booked by a ledger correction, and the message is closed as below ("Closing a corrected payment"). Fix the data cause so the next message does not repeat it. |
| `COMPLETED`, every field matches | The next attempt replays it as a matched 201 and closes the clock. If exhausted, **re-drive** with the command below. |
| `COMPLETED`, money differs / `FAILED` | Do not re-drive. Same as the shared table in step 2: **reverse** a wrong booking, or a finance correction for `FAILED`. |

**Re-drive command (exhausted message).** Four-eyes, like the stop: a ticket records the message id,
the step 1 result that justifies it, the approver and the executor (two different people), and the
executor runs the statement only after the approver has signed off on the ticket. Never re-arm a stopped
message: a stop means someone is remediating it by hand. This re-arms the sweep's bookkeeping for one message and
touches nothing else: never `booking_first_due_at`, `booking_proven_at` or `booking_transaction_id`,
and never a message another pod holds. The sweep then picks it up within a minute, under the same key.

```sql
-- swift-service-db, as the application owner. Expect UPDATE 1; UPDATE 0 means not eligible: stop.
UPDATE swift_messages
SET booking_redrive_attempts = 0, booking_redrive_next_at = NULL, booking_redrive_exhausted_at = NULL
WHERE id = '<messageId>' AND message_type = 'MT103' AND status = 'SENT'
  AND booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
  AND booking_redrive_exhausted_at IS NOT NULL AND booking_redrive_claim IS NULL
  AND booking_redrive_stopped_at IS NULL;
```

Confirm a minute or two later that `booking_proven_at` is set and the status is `COMPLETED`. If it is
counting attempts again, step 1 changed: go back to it.

Do **not** use `ack` or `reject` to make the alert stop: they do not touch the booking clock, so the
alert keeps firing, and a `REJECTED` status on a message the scheme accepted misstates what happened.

## 4. Reverse, reconcile

**Reverse** a wrong booking through transaction-service. `transaction.reverse` is four-eyes, which
takes three calls by two different operators. Approving alone does not execute anything: the maker
must repeat the original call.

```bash
kubectl -n payments port-forward svc/transaction-service 8102:8102 &
TX=<transactionId>
BODY='{"idempotencyKey":"ops-reverse-'$TX'","reason":"#13623 booking does not match payment <paymentId>"}'

# 1. Maker (ROLE_OPERATOR). Answers 202 {"status":"PENDING_APPROVAL","approvalId":"..."}.
curl -sS -X POST "http://localhost:8102/api/v1/transactions/$TX/reverse" \
  -H "Authorization: Bearer $MAKER_TOKEN" -H 'Content-Type: application/json' -d "$BODY"
APPROVAL=<approvalId from the 202>

# 2. Checker (a DIFFERENT ROLE_OPERATOR/ROLE_ADMIN principal). First confirm it is still pending,
#    and that it is the reversal of this transaction:
curl -sS "http://localhost:8102/api/v1/transactions/approvals" -H "Authorization: Bearer $CHECKER_TOKEN" \
  | jq --arg id "$APPROVAL" '.[] | select(.id == $id) | {id, action, resourceId, status, makerId}'
#    expect: action "transaction.reverse", resourceId "$TX", status "PENDING". Then approve:
curl -sS -X PATCH "http://localhost:8102/api/v1/transactions/approvals/$APPROVAL" \
  -H "Authorization: Bearer $CHECKER_TOKEN" -H 'Content-Type: application/json' -d '{"approve":true}'

# 3. Maker repeats the SAME call (same body) with the approval id. Only this executes the reversal.
curl -sS -X POST "http://localhost:8102/api/v1/transactions/$TX/reverse" \
  -H "Authorization: Bearer $MAKER_TOKEN" -H "X-Approval-Id: $APPROVAL" \
  -H 'Content-Type: application/json' -d "$BODY"
```

The checker's own token cannot approve its own request (`SelfApprovalNotAllowedException`). The
approval expires after 24h. The pending list shows only `PENDING`, so once it is approved the id
leaves the list. To prove a reversal has **not** executed yet (approved, step 3 not run), read
transaction-db:

```sql
-- No row: not executed. A REVERSAL row: executed, and the original moves to REVERSED.
SELECT t.id, t.type, t.status, t.completed_at
FROM transaction_idempotency_keys k JOIN transactions t ON t.id = k.transaction_id
WHERE k.idempotency_key = 'ops-reverse-<transactionId>';
SELECT status FROM transactions WHERE id = '<transactionId>';
```

**Reconcile** means recording the decision with finance against the payment id, the key and the
transaction row from step 1: either "booked correctly (historical labels)", or "corrected by journal
<id>". This is a ticket, not a database write.

### Closing a corrected payment

After an out-of-band correction (reverse, adjustment, an accepted historical booking) the payment's
booking clock stays open, because only a strictly matched answer under its own key may close it and
generic status writes are refused. The alert keeps firing for that payment. Silence it in
Alertmanager with the ticket id, scoped to the alert name, and expire the silence when the payment no
longer heads the age query. There is no supported in-band "mark reconciled" operation yet. Do not
write `booking_proven_at` by hand.
