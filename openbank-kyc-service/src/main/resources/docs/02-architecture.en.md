# Architecture

The service follows the OpenBank hexagonal layout (ADR-0002): a framework-free domain, an application layer of use cases, and infrastructure adapters that bridge to HTTP, Kafka and PostgreSQL.

## C4 — container view

```
            ┌──────────────────────────────────────────────────┐
            │                openbank-kyc-service                │
            │                                                    │
  admin-ui ─┤  KycResource (REST, :8114)                         │
   (JWT)    │        │                                           │
            │        ▼                                           │
            │  KycService (application)                          │
            │        │            ▲                              │
            │        ▼            │                              │
            │  KycCaseRepository  PartyEventConsumer ◄── Kafka   │ ◄─ openbank.party.events
            │  (Panache/PG)       (suspend @Incoming)            │
            │        │                                           │
            │        ▼                                           │
            │  kyc_cases / kyc_outbox  ──► KycOutboxDispatcher ──┼─► openbank.kyc.events
            │  (PostgreSQL: openbank_kyc)   (@Scheduled 5s)      │
            └──────────────────────────────────────────────────┘
```

## Hexagonal layers

| Layer | Package | Responsibility |
|---|---|---|
| **Domain** | `com.openbank.kyc.domain.model` | `KycCase`, `KycCheck` and the enums `KycCaseStatus`, `RiskLevel`, `CheckType`, `CheckStatus`. Pure Kotlin, zero framework imports. |
| **Application** | `com.openbank.kyc.application` | `KycService` — open / list / get / update-check / approve / reject use cases, including the idempotent `openCaseForParty`. Defines outbound ports under `application.port.out`. |
| **Adapters — inbound** | `infrastructure.rest`, `infrastructure.kafka` | `KycResource` (REST), `PartyEventConsumer` (`PARTY_CREATED` → auto-open). |
| **Adapters — outbound** | `infrastructure.persistence`, `infrastructure.kafka`, `infrastructure.outbox` | `KycRepository` (Panache), `KafkaKycOutboxEventPublisher`, `KycOutboxDispatcher`. |
| **Cross-cutting** | `infrastructure.authz` | `AuthzProducer` wiring the OPA-backed `@Authorize` (ADR-0034). |

### Key ports (`application.port.out`)

- `KycCaseRepository` — `save`, `update`, `findById`, `findByPartyId`, `listAll`/`listByStatus`, `countAll`/`countByStatus`.
- `KycOutboxPort` / `KycOutboxRepository` — append and drain outbox rows.
- `KycOutboxEventPublisher` — publish an outbox payload to Kafka.

## Outbox → Kafka flow

KYC decisions are propagated with the transactional outbox pattern so a state change and its event are committed atomically:

1. A use case in `KycService` mutates the `KycCase` and (for outbox-backed events) writes a `kyc_outbox` row in the same transaction.
2. `KycOutboxDispatcher` runs every **5 s** (`@Scheduled`, `concurrentExecution = SKIP`), reads up to **25** processable rows, and publishes each through `publishWithResilience`.
3. `publishWithResilience` is wrapped in MicroProfile Fault Tolerance — `@Bulkhead(1)`, `@CircuitBreaker`, `@Retry(2)`, `@Timeout(3000)` — to isolate Kafka outages.
4. On success the row is marked `SENT`; on failure `markFailed` records the error and `attempt_count` for a later retry.

> Note: KYC lifecycle events leave the service ONLY through `kyc_outbox`, written in the same transaction as the case state change (issue #4007) and relayed on `kyc-outbox-out` to topic `openbank.kyc.events`. The direct `kyc-events-out` emitter that used to publish the same events after the commit was removed — two publishers on one topic would race, and only one of them can be atomic.

### Inbound consumer

`PartyEventConsumer` subscribes to `openbank.party.events` (group `kyc-service-party`, `auto.offset.reset=earliest`). It reacts only to `PARTY_CREATED`, ignores other event types, and is **poison-pill safe**: any parse/domain failure is logged and the message is acked. Because `openCaseForParty` is idempotent (re-read on the `uq_kyc_cases_active_party` race), topic replay never creates duplicate open cases.

## Transactions & concurrency

- Reactive stack end-to-end: Hibernate Reactive (Panache) over the Vert.x PG client; REST handlers and the consumer are Kotlin `suspend` functions dispatched on the event loop.
- Domain idempotency is enforced in the database by the partial unique index `uq_kyc_cases_active_party` (V5), guarding against replay and a future multi-pod scale-out.

## Sandbox straight-through processing

`KycService.autoEvaluateAndApprove` exists only for the sandbox: when `openbank.kyc.auto-approve=true`, a case auto-opened from `PARTY_CREATED` has all checks set to `PASSED` and is approved with reviewer `sandbox-auto-approval`, so onboarding completes without an operator. This flag MUST remain `false` in production — the four-eyes approve endpoint is then the only approval path (ADR-0068).
