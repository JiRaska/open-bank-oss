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
sum by (rail, outcome) (increase(openbank_payment_unsettled_total{rail=~"SEPA|DOMESTIC_CZK"}[1h]))
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

## 1. Query transaction-service by key

transaction-service has no REST lookup by idempotency key, so read its database. Read-only:

```bash
kubectl cnpg psql transaction-db -n payments -- -d openbank_transactions
```

```sql
\set key 'sepa-settlement-<paymentId>'   -- or sepa-reversal-<paymentId> / domestic-settlement-<paymentId>
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

Amount and currency must equal the payment's exactly; `value_date` may legitimately be later.

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

## 4. Reverse, reconcile

**Reverse** a wrong booking through transaction-service. `transaction.reverse` is four-eyes: the
maker's call answers with a pending approval, and a second operator approves it.

```bash
kubectl -n payments port-forward svc/transaction-service 8102:8102 &
curl -sS -X POST "http://localhost:8102/api/v1/transactions/<transactionId>/reverse" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"ops-reverse-<transactionId>","reason":"#13623 booking does not match payment <paymentId>"}'
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
