# 114. Standing order execution model — outbox-driven daily sweep

Date: 2026-06-25
Author: Claude (paired with Jiří Raška)
Status: Accepted
Delivery-Status: Shipped

## Context

`openbank-standing-order-service` implements CRUD and lifecycle management for recurring payments
(frequency: DAILY/WEEKLY/BIWEEKLY/MONTHLY/QUARTERLY/ANNUALLY; payment types: SEPA_CREDIT,
DOMESTIC, INTERNAL). The domain model is complete: `StandingOrder.recordExecution(nextDate)` advances
`nextExecutionDate`, decrements end-of-series detection, and transitions to `COMPLETED` when
`endDate` is passed.

**However, no execution engine existed.** `StandingOrderUseCase.listDueForExecution(asOf: LocalDate)`
was defined but no caller invoked it. Standing orders could be created, paused, resumed and cancelled,
but they never executed.

This ADR decides how the execution engine is built.

## Decision

Standing order execution is implemented as a **Quartz-scheduled outbox sweep** (simpler than Temporal
for this use case; Temporal's durability is already used for payment rail workflows — ADR-0101).

**1. Daily sweep.**
`StandingOrderExecutionScheduler` runs at **03:00 UTC** (`"0 0 3 * * ?"`) using Quarkus Scheduler with
`SKIP` concurrency. It calls `StandingOrderService.executeOrders(today)`, which finds all ACTIVE orders
due on or before today.

**2. Atomic dispatch.**
For each due order, the service atomically:
(a) advances `nextExecutionDate` via `StandingOrder.recordExecution()`,
(b) writes a `standing-order.due.v1` outbox event with `idempotencyKey = "so-exec-{id}-{date}"`.
Both operations commit in a single DB transaction — the order can never be advanced without the event
being queued for delivery.

**3. Rail dispatch.**
Downstream consumers (sepa-payment, domestic-payment) subscribe to `openbank.standing-orders.order.event`
and initiate payments keyed on the idempotency key. Exactly-once delivery is guaranteed by the consumer
idempotency store.

**4. Failure handling.**
Rail services call back `PATCH /api/v1/standing-orders/{id}/record-failure` when a payment is rejected.
After **3 consecutive failures** the order transitions to `FAILED` and `standing-order.failed.v1` is
emitted so notification-service can alert the party.
Rail services call `PATCH /api/v1/standing-orders/{id}/record-execution` on confirmed success to reset
the consecutive failure counter.

**5. SCA exemption.**
The dispatch payload carries `scaExemption = RECURRING` (PSD2 RTS Art. 97(3)(c) — recurring transaction
exemption after the first SCA-authenticated setup).

## Alternatives considered

- **Temporal durable workflow per sweep.** Offers full replay history and pod-restart tolerance, but
  adds a Temporal task queue, worker registrar, and activity wrappers for a simple daily sweep where
  the outbox already provides at-least-once delivery and the scheduler SKIP concurrency guard prevents
  overlap. Reconsidered as ADR-0101 complexity; deferred unless sub-daily frequency is needed.
- **Kafka delay topic.** Kafka does not natively support scheduled delivery; requires an external
  scheduler anyway.
- **Temporal Timer per order (perpetual workflow).** Each order gets its own long-running workflow
  sleeping until next execution date. Operationally complex for initial implementation.

## Consequences

**Positive**
- Execution is durable and replayable via Temporal history.
- Idempotency key guarantees exactly-once dispatch even on Temporal retry.
- Failure history is visible in Temporal UI without bespoke logging.

**Negative**
- Temporal dependency for a relatively simple sweep use case.
- Daily granularity only (D); sub-daily frequencies (e.g. intraday sweeps for corporate clients)
  require an hourly trigger extension.
- Sub-daily frequencies (e.g. intraday sweeps for corporate clients) require an hourly trigger extension.

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
