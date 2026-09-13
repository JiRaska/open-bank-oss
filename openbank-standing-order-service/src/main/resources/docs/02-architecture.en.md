# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui / customer app]
  tx[transaction-service]
  audit[audit-service]
  notif[notification]

  so[(standing-order-service)]:::svc
  db[(PostgreSQL<br/>openbank_standing_orders)]
  kafka[(Kafka<br/>standing-orders.order.event)]
  opa[(OPA sidecar)]

  admin -- "POST/GET/DELETE /standing-orders" --> so
  so -- "@Authorize decision" --> opa
  so --> db
  so -- "outbox → publish" --> kafka

  kafka --> tx
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-standing-order-service (Quarkus 3.x)"
    direction TB
    rest[REST<br/>StandingOrderResource]
    uc[Application<br/>StandingOrderService<br/>StandingOrderUseCase]
    dom[Domain<br/>StandingOrder + state machine<br/>domain events]
    persist[Persistence<br/>StandingOrderRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>StandingOrderOutboxDispatcher<br/>@Scheduled every 5s"]
    kpub[Kafka publisher<br/>KafkaStandingOrderOutboxEventPublisher]
    authz[Authz<br/>AuthzProducer → OPA PDP]
  end

  rest --> uc
  rest --> authz
  uc --> dom
  uc --> persist
  outbox --> persist
  outbox --> kpub

  persist -.-> db[(PostgreSQL)]
  kpub -.-> kafka[(Kafka)]
  authz -.-> opa[(OPA sidecar)]
```

## Hexagonal layers

The package structure reflects **ports-and-adapters**:

```
com.openbank.standingorder/
├── domain/                    ◄── core — no framework dependencies
│   ├── model/                 StandingOrder, StandingOrderStatus, Frequency, PaymentType
│   └── event/                 StandingOrderCreated / Executed / Cancelled
│
├── application/               ◄── use-case orchestration
│   ├── port/in/               StandingOrderUseCase, CreateStandingOrderCommand
│   ├── port/out/              StandingOrderRepository, StandingOrderOutboxRepository,
│   │                          StandingOrderOutboxEventPublisher
│   └── usecase/               StandingOrderService
│
└── infrastructure/            ◄── adapters
    ├── rest/                  StandingOrderResource, DTOs + mapping
    ├── persistence/           entity / repository / mapper (Hibernate Reactive)
    ├── outbox/                StandingOrderOutboxDispatcher (@Scheduled)
    ├── kafka/                 KafkaStandingOrderOutboxEventPublisher
    └── authz/                 AuthzProducer (OPA PolicyDecisionPoint)
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. The domain (`StandingOrder`) holds the state-transition logic (`pause`, `resume`, `cancel`, `recordExecution`) and never sees JPA, Kafka, or REST DTOs.

### Domain state machine

```
            create
              │
              ▼
          ┌────────┐  pause   ┌────────┐
          │ ACTIVE │ ───────► │ PAUSED │
          │        │ ◄─────── │        │
          └───┬────┘  resume  └───┬────┘
              │ cancel            │ cancel
              ▼                   ▼
          ┌───────────┐      ┌───────────┐
          │ CANCELLED │      │ CANCELLED │
          └───────────┘      └───────────┘

  recordExecution → COMPLETED (when endDate passed) ; FAILED on execution error
```

Invariants enforced in the aggregate: only `ACTIVE` orders can be paused, only `PAUSED` orders can be resumed, and `CANCELLED`/`COMPLETED` orders cannot be cancelled again (`require(...)` guards).

## Outbox flow

```mermaid
sequenceDiagram
  participant C as Client
  participant R as StandingOrderResource
  participant S as StandingOrderService
  participant DB as PostgreSQL
  participant D as OutboxDispatcher
  participant K as Kafka

  C->>R: POST /standing-orders (idempotencyKey)
  R->>S: create(command)
  S->>DB: findByIdempotencyKey (dedup)
  S->>DB: INSERT standing_orders
  S->>DB: INSERT standing_order_outbox (status=PENDING)
  R-->>C: 201 Created

  loop every 5s (@Scheduled, SKIP concurrent)
    D->>DB: listProcessable(batch=25)
    D->>K: publish to openbank.standing-orders.order.event
    D->>DB: markSent(eventId) / markFailed(eventId, error)
  end
```

**Why outbox:** transactional consistency between the DB write and the Kafka publish. Delivery is at-least-once; the dispatcher wraps the publish in a **resilience stack** — `@CircuitBreaker` (volume 10, ratio 0.5), `@Retry` (2 retries, jitter), `@Bulkhead` (1), `@Timeout` (3 s) — and the scheduler swallows errors so it never crashes the loop.

## Key ports

| Port (direction) | Defined in | Adapter |
|---|---|---|
| `StandingOrderUseCase` (in) | `application/port/in` | `StandingOrderResource` calls it |
| `StandingOrderRepository` (out) | `application/port/out` | `StandingOrderRepositoryImpl` (Hibernate Reactive) |
| `StandingOrderOutboxRepository` (out) | `application/port/out` | `StandingOrderOutboxRepositoryImpl` |
| `StandingOrderOutboxEventPublisher` (out) | `application/port/out` | `KafkaStandingOrderOutboxEventPublisher` |
| `PolicyDecisionPoint` (out) | `openbank-libs` authz | `AuthzProducer` → OPA sidecar |

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.authz.@Authorize` + `PolicyDecisionPoint` | declarative authorization on mutations (e.g. `standingOrder.pause`) |
| `libs.authz.OpaSidecarPolicyDecisionPoint` | OPA sidecar client (advisory/enforce) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |

## Principles

1. **Aggregate boundary = StandingOrder** — every lifecycle operation is atomic on a single order.
2. **Domain events first** — state changes emit domain events; infrastructure serializes them to the outbox.
3. **No remote calls in TX** — synchronous within request-response, async via outbox + Kafka.
4. **Idempotent creation** — `idempotencyKey` is unique in the DB; a repeat create returns the existing order.
5. **Decoupled execution** — this service records intent; downstream services execute the payment.
