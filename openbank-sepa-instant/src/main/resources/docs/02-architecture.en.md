# Architecture

The service follows the hexagonal (ports & adapters) architecture mandated by [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md). The domain layer has **zero framework imports**.

## C4 — container view

```
        ┌──────────────────────────────────────────────────────────────┐
        │  openbank-sepa-instant  (Quarkus, port 8127 / mgmt 8085)       │
        │                                                                │
        │  REST adapter ──► application use-cases ──► domain             │
        │       │                   │                                    │
        │       │                   ├─► SanctionsScreeningPort ─────────►│──► sanctions-service
        │       │                   ├─► AmlCasePort ────────────────────►│──► aml-service
        │       │                   ├─► SctInstPaymentRepository ───────►│──► PostgreSQL
        │       │                   └─► SctInstEventPublisher ──────────►│──► openbank.sepa.instant.events
        │       │                       (direct Kafka emitter, no outbox)│
        └──────────────────────────────────────────────────────────────┘
```

## Hexagonal layers

### Domain (`domain/`)
Pure Kotlin, no Quarkus.

- `model/SctInstPayment` — the aggregate (data class) and `SctInstStatus` enum (`PENDING, PROCESSING, SETTLED, REJECTED, TIMEOUT, RECALLED`).
- `event/SctInstEvents` — sealed `SctInstEvent` hierarchy: `SctInstPaymentSubmitted`, `SctInstPaymentSettled`, `SctInstPaymentRejected`, `SctInstPaymentTimeout`, `SctInstPaymentRecalled`.
- `screening/ScreeningPolicy` — the pure decision object. `decide(results)` returns `BLOCK > REVIEW > CLEAR`:
  - **BLOCK** — any `HIT`, any `ESCALATED`, or a `POTENTIAL_HIT` strictly above `POTENTIAL_HIT_BLOCK_THRESHOLD = 0.85`.
  - **REVIEW** — any sub-threshold `POTENTIAL_HIT` (false-positive candidate → human review).
  - **CLEAR** — everything else (`CLEAR` / `WHITELISTED`, including an empty result set).
  The threshold deliberately mirrors the sanctions service's own `isHighRisk` so the two never drift.

### Application (`application/`)
Use-cases and ports.

- **Inbound ports** (`port/in`): `SubmitSctInstPaymentUseCase`, `GetSctInstPaymentUseCase`, `RecallSctInstPaymentUseCase` + `SubmitSctInstCommand`.
- **Outbound ports** (`port/out`): `SctInstPaymentRepository`, `SctInstEventPublisher`, `SanctionsScreeningPort` (+ `ScreeningUnavailableException`), `AmlCasePort` (+ `OpenAmlCaseCommand`, `AmlCaseRiskLevel`).
- `usecase/SctInstPaymentService` — orchestrates the screening gate (see flow below).

### Adapters (`infrastructure/`)
- `rest/SctInstResource` — JAX-RS resource at `/api/v1/sepa-instant`; `@Authorize(action = "sctInstPayment.recall", …)` on recall (ADR-0034).
- `rest/ExceptionMappers` — `NotFoundException → 404`, `BadRequestException → 400`.
- `client/SanctionsScreeningAdapter` + `SanctionsServiceClient` — REST client to sanctions-service; maps remote status onto the local `ScreeningMatchStatus`, raises `ScreeningUnavailableException` when unreachable.
- `client/AmlCaseAdapter` + `AmlServiceClient` — REST client to aml-service case store.
- `persistence/` — `SctInstPaymentEntity`, Panache reactive repository, `SctInstMapper`.
- `kafka/` — `KafkaSctInstEventPublisher`.
- `authz/AuthzProducer` — wires the libs authz client (ADR-0034).

## Screening gate flow (ADR-0032, instant-rail adaptation)

On `submit(command)`:

1. **Idempotency check** — `repo.findByIdempotencyKey`; if a record exists, return it unchanged.
2. Build the base payment (`status = PENDING`, `submittedAt = now`).
3. **Screen debtor name, then creditor name** synchronously via `SanctionsScreeningPort`.
4. `ScreeningPolicy.decide(results)`:
   - **CLEAR → proceed**: `status = PROCESSING`, arm `executionTimeoutAt = now + execution-timeout-seconds (10s)`, persist, publish `SctInstPaymentSubmitted`.
   - **REVIEW → hold**: persist `PENDING`, open a **HIGH** AML case (`AML_HOLD`); never settle.
   - **BLOCK → reject**: persist `REJECTED` (`reason = SANCTIONS_HIT`), open a **CRITICAL** AML case, publish `SctInstPaymentRejected`.
5. **Screening outage** (`ScreeningUnavailableException`) → **fail closed**: hold `PENDING`, open a **MEDIUM** AML case (`SCREENING_UNAVAILABLE`). The payment is never released un-screened (ADR-0032 §C).

Opening the AML case is **best-effort** (`openCaseQuietly`): a case-store outage logs an error but must never flip the screening verdict already rendered.

## Kafka publishing

Lifecycle events (`SctInstPaymentSubmitted`/`Settled`/`Rejected`/…) are published directly from `SctInstPaymentService` at each transition via `SctInstEventPublisher` → `KafkaSctInstEventPublisher`, which emits to the `sct-inst-events-out` channel (Kafka topic `openbank.sepa.instant.events`). This is a direct, synchronous-emitter publish — not a transactional outbox — so delivery is not atomic with the DB write. An earlier transactional-outbox pipeline (`SctInstOutboxPort`/`SctInstOutboxDispatcher`) was built but never wired to a real call site and has been removed (issue #1034); `KafkaSctInstEventPublisher` was always the pipeline actually in use.

## Resilience & rate limiting

Configured under `openbank.resilience` / `openbank.rate-limit` (SmallRye Fault Tolerance): circuit breaker (volume 20, failure ratio 0.3, success threshold 10, 5 s delay), retry (max 2, 100 ms delay, 50 ms jitter), timeout (10 s), and a concurrency cap (`max-concurrent-requests: 500`).
