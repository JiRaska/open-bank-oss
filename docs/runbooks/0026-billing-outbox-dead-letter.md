# Billing: dead-lettered outbox rows

Operational runbook for billing-service dead-lettered outbox rows. Kept here rather than in the
generated svc-billing.md, which generate-service-runbooks.py owns — hand edits there are drift (#2255).

Alerts: `BillingOutboxDeadLetteredNew` (critical, the DEAD count just rose) and
`BillingOutboxDeadLettered` (warning, DEAD rows are still unresolved). A `billing_outbox` row
in `DEAD` is a fee assessed but never posted to the ledger (or, for
`billing.annual-fee-summary.ready`, a summary never sent to Kafka). Nothing retries it.

1. **Read before touching anything.** Read-only, on the billing database:
   `SELECT id, event_type, aggregate_id, attempt_count, last_error, created_at, updated_at
   FROM billing_outbox WHERE status = 'DEAD' ORDER BY created_at;`
2. **Classify by `last_error`.** A transport error (`circuit breaker is open`, connection refused,
   `localhost:9092`) means the target was unreachable or not configured when the row died.
   Confirm that cause is fixed before any replay. Anything else (4xx from ledger,
   deserialization) is a per-row defect: fix the cause first, or the replay dead-letters again.
3. **Check the ledger before replaying a charge or reversal.** The journal POST is idempotent
   on the payload's `idempotencyKey`, so a replay of a row the ledger already booked is a
   no-op. Still confirm the fee's state in `billing_assessment` so the disposition is recorded.
4. **Replay** is a data change on a money-path table: four-eyes, attributable, via the
   approved change process. Set the chosen rows back to `PENDING` with `attempt_count = 0`
   and `last_error` cleared; the dispatcher picks them up on its next tick. Never bulk-requeue
   without step 2.
5. **Write-off** (the fee should not be charged) is a business decision: record it, then move
   the row out of `DEAD` the same attributable way so the warning clears.

The warning clears only when no `DEAD` rows remain; the critical alert clears once the count
stops rising.
