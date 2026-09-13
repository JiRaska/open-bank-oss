# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui / channels]
  sanc[sanctions-service]
  aml[aml-service]
  clr[clearing-service]
  led[ledger-service]
  audit[audit-service]
  notif[notification]
  opa[OPA sidecar]

  sepa[(sepa-payment-service)]:::svc
  db[(PostgreSQL<br/>openbank_sepa_payments)]
  kafka[(Kafka<br/>openbank.sepa.payment.events)]
  redis[(Valkey<br/>idempotency)]

  admin -- "POST/GET/PATCH /sepa-payments" --> sepa
  sepa -- "screen (sync, fail-closed)" --> sanc
  sepa -- "open case (best-effort)" --> aml
  sepa -. "authz check" .-> opa

  sepa --> db
  sepa -- "outbox → publish" --> kafka
  sepa --> redis

  kafka --> clr
  kafka --> led
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-sepa-payment (Quarkus 3.x)"
    direction TB
    rest[REST<br/>SepaPaymentResource<br/>ExceptionMappers]
    uc[Application<br/>SepaPaymentService]
    dom[Domain<br/>SepaPayment + state machine<br/>ScreeningPolicy<br/>+ domain events]
    persist[Persistence<br/>SepaPaymentRepositoryImpl<br/>SepaPaymentOutboxRepositoryImpl]
    outbox["Outbox<br/>SepaPaymentOutboxDispatcher<br/>@Scheduled every 5s"]
    kafkaad[Kafka<br/>KafkaSepaPaymentEventPublisher]
    clients[Clients<br/>SanctionsScreeningAdapter<br/>AmlCaseAdapter]
    idem[Idempotency<br/>libs IdempotencyStore]
  end

  rest --> uc
  rest --> idem
  uc --> dom
  uc --> persist
  uc --> clients
  outbox --> kafkaad

  persist -.-> db[(PostgreSQL)]
  kafkaad -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
  clients -.-> ext[sanctions / aml]
```

## Hexagonal layers

The package layout reflects **ports-and-adapters**:

```
com.openbank.sepa/
├── domain/                    ◄── core — no framework dependencies
│   ├── model/                 SepaPayment, SepaPaymentStatus, SepaPaymentType, SepaRejectReason
│   ├── event/                 SepaPaymentCreatedEvent, SepaPaymentStatusChangedEvent
│   └── screening/             ScreeningPolicy, ScreeningResult, ScreeningDecision (pure)
│
├── application/               ◄── use-case orchestration
│   ├── port/in/               SepaPaymentUseCase + commands/queries
│   ├── port/out/              SepaPaymentRepository, SepaPaymentOutboxRepository,
│   │                          SepaPaymentEventPublisher, SanctionsScreeningPort, AmlCasePort
│   └── usecase/               SepaPaymentService
│
└── infrastructure/            ◄── adapters
    ├── rest/                  SepaPaymentResource, DTOs, ExceptionMappers
    ├── persistence/           entity + repository impls + mapper
    ├── outbox/                SepaPaymentOutboxDispatcher (scheduled)
    ├── kafka/                 KafkaSepaPaymentEventPublisher
    ├── client/                SanctionsScreeningAdapter, AmlCaseAdapter (+ REST clients)
    ├── idempotency/           IdempotencyConfig
    └── authz/                 AuthzProducer (OPA, ADR-0034)
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. The domain — including the `ScreeningPolicy` decision and the `SepaPayment` state machine — has zero framework imports.

## Screening gate (ADR-0032)

```mermaid
sequenceDiagram
  participant C as Client
  participant R as SepaPaymentResource
  participant S as SepaPaymentService
  participant DB as PostgreSQL
  participant Sanc as sanctions-service
  participant Aml as aml-service

  C->>R: POST /sepa-payments (Idempotency-Key)
  R->>S: createPayment(...)
  S->>DB: INSERT payment (RECEIVED) + outbox (created) — one TX
  S->>Sanc: screen(debtorName) + screen(creditorName)
  alt screening unavailable
    Sanc--xS: error
    S->>Aml: openCase(MEDIUM, SCREENING_UNAVAILABLE) [best-effort]
    Note over S: payment HELD in RECEIVED (fail-closed)
  else CLEAR
    S->>DB: transition → VALIDATED + outbox (status-changed)
  else REVIEW (sub-threshold potential hit)
    S->>Aml: openCase(HIGH, AML_HOLD) [best-effort]
    Note over S: payment HELD in RECEIVED for human decision
  else BLOCK (hit / escalated / score > 0.85)
    S->>Aml: openCase(CRITICAL, SANCTIONS_HIT) [best-effort]
    S->>DB: transition → REJECTED (SANCTIONS_HIT) + outbox
  end
  R-->>C: 201 Created (final status reflects the verdict)
```

`ScreeningPolicy.decide` is pure and mirrors the sanctions service's own `isHighRisk` threshold (`POTENTIAL_HIT_BLOCK_THRESHOLD = 0.85`): **BLOCK** dominates **REVIEW** dominates **CLEAR**. Opening the AML case is best-effort — a case-store outage must never flip the screening verdict already rendered.

## Outbox flow

```mermaid
sequenceDiagram
  participant S as SepaPaymentService
  participant DB as PostgreSQL
  participant D as SepaPaymentOutboxDispatcher
  participant K as Kafka

  S->>DB: BEGIN TX
  S->>DB: INSERT/UPDATE sepa_payments
  S->>DB: INSERT sepa_payment_outbox (status=PENDING)
  S->>DB: COMMIT

  loop every 5s (concurrentExecution = SKIP)
    D->>DB: SELECT processable FROM sepa_payment_outbox LIMIT 25
    D->>K: publishWithResilience(payload) [CircuitBreaker + Retry + Timeout + Bulkhead]
    alt published
      D->>DB: markSent(eventId)
    else failed
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Why outbox:** transactional consistency between the DB write and the Kafka publish. At-least-once delivery; downstream consumers must be idempotent. The dispatcher wraps the publish in MicroProfile Fault Tolerance (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`) so a broker outage degrades to retry rather than crashing the scheduler.

## Key ports

| Port (application/port) | Direction | Adapter | Purpose |
|---|---|---|---|
| `SepaPaymentUseCase` | in | `SepaPaymentResource` | create / get / list / transition |
| `SepaPaymentRepository` | out | `SepaPaymentRepositoryImpl` | persist aggregate + outbox in one TX |
| `SepaPaymentOutboxRepository` | out | `SepaPaymentOutboxRepositoryImpl` | drain outbox (listProcessable / markSent / markFailed) |
| `SepaPaymentEventPublisher` | out | `KafkaSepaPaymentEventPublisher` | serialize payloads + publish to Kafka |
| `SanctionsScreeningPort` | out | `SanctionsScreeningAdapter` | synchronous screen, throws `ScreeningUnavailableException` (fail-closed) |
| `AmlCasePort` | out | `AmlCaseAdapter` | best-effort `openCase` on hit/hold |

## Principles

1. **Aggregate boundary = SepaPayment** — every state change is atomic and emits a domain event.
2. **Screen before release** — the RECEIVED row is durably persisted *before* screening, so the payment is never lost if screening then fails; fail-closed holds it.
3. **Transactional outbox** — DB row and outbox message commit together; Kafka publish is asynchronous via the dispatcher.
4. **Idempotence at the edge** — `Idempotency-Key` mandatory on create; deduplication in Redis and by `idempotency_key` UNIQUE in the DB.
5. **No verdict-flipping side effects** — AML case opening is best-effort and never changes the screening decision.
