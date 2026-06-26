# 113. Standing order execution model — Temporal-driven daily sweep (NOT YET IMPLEMENTED)

Date: 2026-06-25
Author: Claude (paired with Jiří Raška)
Status: Proposed

## Context

`openbank-standing-order-service` implements CRUD and lifecycle management for recurring payments
(frequency: DAILY/WEEKLY/BIWEEKLY/MONTHLY/QUARTERLY/ANNUALLY; payment types: SEPA_CREDIT,
DOMESTIC, INTERNAL). The domain model is complete: `StandingOrder.recordExecution(nextDate)` advances
`nextExecutionDate`, decrements end-of-series detection, and transitions to `COMPLETED` when
`endDate` is passed.

**However, no execution engine exists.** `StandingOrderUseCase.listDueForExecution(asOf: LocalDate)`
is defined in the port-in interface and implemented in `StandingOrderService`, but as of
2026-06-25 no caller invokes it — not a Temporal workflow, not a Quartz job, not any other scheduler.
Standing orders can be created, paused, resumed and cancelled, but they never execute.

This ADR decides how the execution engine will be built.

## Decision

We will implement standing order execution as a **Temporal durable workflow** (ADR-0101), triggered
by a daily schedule at **03:00 UTC**.

**1. Daily sweep workflow.**
A Temporal `CronSchedule("0 3 * * *")` workflow calls `listDueForExecution(today)` and fans out
one child workflow per due order. The sweep is idempotent: if re-triggered on the same day, child
workflows deduplicate via `idempotencyKey = "so-{id}-{nextExecutionDate}"`.

**2. Payment dispatch per child workflow.**
Each child workflow dispatches to the appropriate rail via REST activity:
- `SEPA_CREDIT` → `POST /api/v1/sepa-payments` on sepa-payment-service
- `DOMESTIC` → `POST /api/v1/domestic-payments` on domestic-payment-service
- `INTERNAL` → direct ledger transfer (balance-service debit + credit, no payment rail)

**3. Success path.**
On HTTP 2xx from the rail service, the child workflow calls
`standing-order-service PATCH /api/v1/standing-orders/{id}/record-execution` to advance
`nextExecutionDate` and increment `executionCount`. If `endDate` is reached, status transitions to
`COMPLETED`.

**4. Failure handling.**
On rail rejection (insufficient funds, validation error) or timeout, the child workflow increments
`failureCount`. After 3 consecutive failures the order transitions to `FAILED` and emits
`standing-order.failed.v1` → notification-service sends an alert to the party.

**5. SCA exemption.**
Standing orders qualify as PSD2 recurring transaction SCA exemption (Art. 97(3)(c)) after the
first SCA-authenticated setup. The dispatch payload must carry `scaExemption = RECURRING`.

**6. Status after this ADR is implemented.**
This ADR will be marked **Accepted** once the Temporal workflow and the
`record-execution` endpoint exist and pass the boot smoke test.

## Alternatives considered

- **Quartz `@Scheduled` inside the service.** Simple, but not tolerant to pod restarts mid-sweep
  of hundreds of orders; no built-in retry/history visibility.
- **Kafka delay topic.** Kafka does not natively support scheduled delivery; requires an external
  scheduler anyway.
- **Temporal Timer per order (perpetual workflow).** Each order gets its own long-running workflow
  sleeping until next execution date. Scales well but operationally complex; chosen approach is
  simpler for initial implementation.

## Consequences

**Positive**
- Execution is durable and replayable via Temporal history.
- Idempotency key guarantees exactly-once dispatch even on Temporal retry.
- Failure history is visible in Temporal UI without bespoke logging.

**Negative**
- Temporal dependency for a relatively simple sweep use case.
- Daily granularity only (D); sub-daily frequencies (e.g. intraday sweeps for corporate clients)
  require an hourly trigger extension.
- `record-execution` endpoint does not exist yet — must be added to standing-order-service.

**Neutral**
- The `INTERNAL` payment type (own-account transfer) bypasses the payment rail entirely; it needs
  direct balance-service integration rather than going through customer-edge.

## Compliance impact

- PSD2: first standing-order payment requires SCA; subsequent payments use the recurring exemption
  (Art. 97(3)(c) RTS). The dispatch payload must signal the exemption.
- DORA: standing-order execution is a scheduled money-path operation → T0 SLA (ADR-0061).
- GDPR: not applicable — no new PII processed.
- ČNB: not applicable specifically for this ADR.

## References

- `openbank-standing-order-service/src/main/kotlin/.../domain/model/StandingOrder.kt`
- `openbank-standing-order-service/src/main/kotlin/.../application/port/in/StandingOrderPorts.kt`
- ADR-0101 (Temporal durable execution)
- ADR-0004 (saga pattern)
- PSD2 RTS Art. 97(3)(c) — recurring transaction SCA exemption
