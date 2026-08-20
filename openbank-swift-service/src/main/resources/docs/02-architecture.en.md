# Architecture

Hexagonal architecture per [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md).

## C4 — System Context

```mermaid
graph LR
  pay[payment-services / operators]
  gw[SWIFT gateway / counterparty]
  admin[admin-ui]

  swift[(swift-service)]:::svc
  db[(PostgreSQL<br/>openbank_swift)]
  kafka[(Kafka<br/>openbank.payments.swift.event)]
  redis[(Valkey)]
  opa[OPA sidecar]

  txn[transaction-service]
  aml[aml-service]
  audit[audit-service]

  pay -- "POST /api/v1/swift" --> swift
  gw -- "ack / reject" --> swift
  admin -- "GET status / messages" --> swift

  swift --> db
  swift -- "outbox → publish" --> kafka
  swift -. "idempotency/cache" .-> redis
  swift -. "authz decision" .-> opa

  kafka --> txn
  kafka --> aml
  kafka --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-swift-service (Quarkus 3.x, JDK 25)"
    direction TB
    rest[REST<br/>SwiftResource<br/>+ SwiftDtos]
    uc[Application<br/>SwiftService : SwiftUseCase]
    dom[Domain<br/>SwiftMessage + enums<br/>validate]
    persist[Persistence<br/>SwiftRepositoryImpl<br/>Hibernate Reactive Panache]
    outbox["Outbox<br/>SwiftOutboxDispatcher<br/>@Scheduled every 5s"]
    kafkap[Kafka<br/>KafkaSwiftOutboxEventPublisher]
    authz[Authz<br/>AuthzProducer → OPA]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  outbox --> kafkap
  rest -.-> authz

  persist -.-> db[(PostgreSQL)]
  kafkap -.-> kafka[(Kafka)]
  authz -.-> opa[(OPA sidecar)]
```

## Hexagonal layers

The package structure (`com.openbank.swift`) reflects **ports-and-adapters**:

```
com.openbank.swift/
├── domain/
│   └── model/                 SwiftMessage, SwiftMessageType,
│                              SwiftStatus, SwiftPriority + validate()
│
├── application/               ◄── use-case orchestration
│   ├── port/in/               SendSwiftCommand, SwiftUseCase (inbound port)
│   ├── port/out/              SwiftRepository, SwiftOutboxRepository,
│   │                          SwiftOutboxEventPublisher (outbound ports)
│   └── usecase/               SwiftService (implements SwiftUseCase)
│
└── infrastructure/            ◄── adapters
    ├── rest/                  SwiftResource (JAX-RS), dto/SwiftDtos
    ├── persistence/           SwiftRepositoryImpl, SwiftOutboxRepositoryImpl,
    │                          entity/, mapper/
    ├── outbox/                SwiftOutboxDispatcher (@Scheduled)
    ├── kafka/                 KafkaSwiftOutboxEventPublisher
    └── authz/                 AuthzProducer (OPA PDP bean)
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. Domain code (the `SwiftMessage` aggregate and its `validate()`) has no framework imports.

## Key ports

| Port | Direction | Defined in | Adapter |
|---|---|---|---|
| `SwiftUseCase` | inbound | `application/port/in` | `SwiftService` |
| `SwiftRepository` | outbound | `application/port/out` | `SwiftRepositoryImpl` (Panache) |
| `SwiftOutboxRepository` | outbound | `application/port/out` | `SwiftOutboxRepositoryImpl` |
| `SwiftOutboxEventPublisher` | outbound | `application/port/out` | `KafkaSwiftOutboxEventPublisher` |
| `PolicyDecisionPoint` | outbound | `libs.authz` | `OpaSidecarPolicyDecisionPoint` via `AuthzProducer` |

## Outbox → Kafka flow

```mermaid
sequenceDiagram
  participant C as Client
  participant R as SwiftResource
  participant S as SwiftService
  participant DB as PostgreSQL
  participant D as SwiftOutboxDispatcher
  participant K as Kafka

  C->>R: POST /api/v1/swift (idempotencyKey)
  R->>S: send(cmd)
  S->>DB: findByIdempotencyKey → if present, return existing
  S->>S: validate() (BIC, ref, amount, charge code)
  S->>DB: INSERT swift_messages (status=VALIDATED)
  R-->>C: 201 Created

  loop @Scheduled every 5s (SKIP if running)
    D->>DB: listProcessable(25) from swift_outbox (status=PENDING)
    D->>K: publishWithResilience(payload) → topic openbank.payments.swift.event
    D->>DB: markSent(eventId) / markFailed(eventId, error)
  end
```

**Resilience on dispatch** (`SwiftOutboxDispatcher.publishWithResilience`, SmallRye Fault Tolerance): `@Bulkhead(1)`, `@CircuitBreaker(volume=10, ratio=0.5, delay=5s, success=2)`, `@Retry(max=2, delay=200ms, jitter=100ms)`, `@Timeout(3000ms)`. The scheduler catches and swallows exceptions so it never crashes; per-event failures are recorded via `markFailed` with `last_error` and `attempt_count`.

> **Implementation note (grounded in code):** the `SwiftService.send` path persists the aggregate but does not currently insert a row into `swift_outbox`. The outbox repository, dispatcher and Kafka publisher are fully implemented; the use-case-side write that enqueues a domain event is the missing wire (TBD / follow-up).

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.authz.Authorize` | `@Authorize(action="swift.acknowledge", resource="#id")` on ack |
| `libs.authz.PolicyDecisionPoint` / `OpaSidecarPolicyDecisionPoint` | OPA sidecar decision, produced by `AuthzProducer` |
| service-info / docs plumbing | `/api/v1/info`, `/q/openbank/docs` (this documentation) |

## Principles

1. **Aggregate boundary = SwiftMessage** — each create/ack/reject is an atomic transition.
2. **Idempotent submit** — `idempotencyKey` deduplicated at the use case and by a DB `UNIQUE` constraint.
3. **Transactional outbox** — DB write and Kafka publish are decoupled; at-least-once delivery, idempotent consumers.
4. **Authorization at the edge** — OPA-gated actions (`@Authorize`), advisory by default, enforceable via `AUTHZ_ENFORCE`.
5. **Money-path discipline** — high-value wire surface; message authenticity is the dominant control (see [06 — Compliance](./06-compliance.md) and the threat model).
