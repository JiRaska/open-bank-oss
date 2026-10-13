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
        │       │                   ├─► payment + outbox ────────────────►│──► PostgreSQL
        │       │                   └─► outbox relay ────────────────────►│──► Kafka events
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
- **Outbound ports** (`port/out`): `SctInstPaymentRepository`, `SctInstOutboxRepository`, `SanctionsScreeningPort` (+ `ScreeningUnavailableException`), `AmlCasePort` (+ `OpenAmlCaseCommand`, `AmlCaseRiskLevel`).
- `usecase/SctInstPaymentService` — orchestrates the screening gate (see flow below).

### Adapters (`infrastructure/`)
- `rest/SctInstResource` — JAX-RS resource at `/api/v1/sepa-instant`; `@Authorize(action = "sctInstPayment.recall", …)` on recall (ADR-0034).
- `rest/ExceptionMappers` — `NotFoundException → 404`, `BadRequestException → 400`.
- `client/SanctionsScreeningAdapter` + `SanctionsServiceClient` — REST client to sanctions-service; maps remote status onto the local `ScreeningMatchStatus`, raises `ScreeningUnavailableException` when unreachable.
- `client/AmlCaseAdapter` + `AmlServiceClient` — REST client to aml-service case store.
- `persistence/` — payment and outbox entities, reactive repositories, `SctInstMapper`.
- `outbox/` — `SctInstOutboxDispatcher` claims and retries committed events.
- `kafka/` — `KafkaSctInstEventPublisher` sends the stored four-field payload.
- `authz/AuthzProducer` — wires the libs authz client (ADR-0034).

## Screening gate flow (ADR-0032, instant-rail adaptation)

On `submit(command)`:

1. **Idempotency check** — `repo.findByIdempotencyKey`; if a record exists, return it unchanged.
2. Build the base payment (`status = PENDING`, `submittedAt = now`).
3. **Screen debtor name, then creditor name** synchronously via `SanctionsScreeningPort`.
4. `ScreeningPolicy.decide(results)`:
   - **CLEAR → proceed**: `status = PROCESSING`, arm `executionTimeoutAt = now + execution-timeout-seconds (10s)`, persist payment and `SctInstPaymentSubmitted` outbox row together.
   - **REVIEW → hold**: persist `PENDING`, open a **HIGH** AML case (`AML_HOLD`); never settle.
   - **BLOCK → reject**: persist `REJECTED` (`reason = SANCTIONS_HIT`) and its event row together; open a **CRITICAL** AML case.
5. **Screening outage** (`ScreeningUnavailableException`) → **fail closed**: hold `PENDING`, open a **MEDIUM** AML case (`SCREENING_UNAVAILABLE`). The payment is never released un-screened (ADR-0032 §C).

Opening the AML case is **best-effort** (`openCaseQuietly`): a case-store outage logs an error but must never flip the screening verdict already rendered.

## Kafka publishing

The #12181 source candidate writes each event-producing payment transition and its outbox row in one PostgreSQL transaction. `SctInstOutboxDispatcher` later claims the row and retries delivery through `KafkaSctInstEventPublisher` to `openbank.sepa.instant.events`. This is at-least-once delivery: a broker acknowledgement followed by failure before `markSent` can replay the same event. The existing four-field Kafka payload stays unchanged; the record is now keyed by the payment id (per-payment ordering), and standard outbox headers carry the durable `ce-id` on every attempt. Audit-service uses that ID before the Kafka offset when the body has no `eventId`, so the audit consumer must be deployed before this producer. This describes source behavior, not proof that the candidate was reviewed, merged, or deployed.

| Payment transition | Existing domain event | Candidate delivery guarantee |
| --- | --- | --- |
| New payment to `PROCESSING` | `SctInstPaymentSubmitted` | Payment and event row commit together; relay retries until sent or visible `DEAD`. |
| New payment to `REJECTED` (screening or scheme) | `SctInstPaymentRejected` | Payment and event row commit together; same relay policy. |
| `PROCESSING` to `SETTLED` | `SctInstPaymentSettled` | Status and event row commit together under the payment lock; same relay policy. |
| `SETTLED` to `RECALLED` | `SctInstPaymentRecalled` | Status and event row commit together under the payment lock; same relay policy. |
| New payment held at `PENDING` | No payment-domain event in the established schema | The payment row is durable, while opening the separate AML case remains best effort. No payment event delivery is claimed. |

An idempotent repeat of an existing submission returns the existing payment without creating a second event. Historical transitions are not reconstructed by this migration; any missing audit fact needs separate approved reconciliation.

## Resilience & rate limiting

Configured under `openbank.resilience` / `openbank.rate-limit` (SmallRye Fault Tolerance): circuit breaker (volume 20, failure ratio 0.3, success threshold 10, 5 s delay), retry (max 2, 100 ms delay, 50 ms jitter), timeout (10 s), and a concurrency cap (`max-concurrent-requests: 500`).
