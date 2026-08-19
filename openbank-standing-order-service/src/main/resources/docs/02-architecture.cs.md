# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui / zákaznická app]
  tx[transaction-service]
  audit[audit-service]
  notif[notification]

  so[(standing-order-service)]:::svc
  db[(PostgreSQL<br/>openbank_standing_orders)]
  kafka[(Kafka<br/>standing-orders.order.event)]
  opa[(OPA sidecar)]

  admin -- "POST/GET/DELETE /standing-orders" --> so
  so -- "@Authorize rozhodnutí" --> opa
  so --> db
  so -- "outbox → publish" --> kafka

  kafka --> tx
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-standing-order-service (Quarkus 3.x)"
    direction TB
    rest[REST<br/>StandingOrderResource]
    uc[Application<br/>StandingOrderService<br/>StandingOrderUseCase]
    dom[Domain<br/>StandingOrder + stavový automat<br/>doménové události]
    persist[Persistence<br/>StandingOrderRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>StandingOrderOutboxDispatcher<br/>@Scheduled každých 5s"]
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

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters**:

```
com.openbank.standingorder/
├── domain/                    ◄── jádro — bez frameworkových závislostí
│   ├── model/                 StandingOrder, StandingOrderStatus, Frequency, PaymentType
│   └── event/                 StandingOrderCreated / Executed / Cancelled
│
├── application/               ◄── orchestrace případů užití
│   ├── port/in/               StandingOrderUseCase, CreateStandingOrderCommand
│   ├── port/out/              StandingOrderRepository, StandingOrderOutboxRepository,
│   │                          StandingOrderOutboxEventPublisher
│   └── usecase/               StandingOrderService
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  StandingOrderResource, DTO + mapování
    ├── persistence/           entity / repository / mapper (Hibernate Reactive)
    ├── outbox/                StandingOrderOutboxDispatcher (@Scheduled)
    ├── kafka/                 KafkaStandingOrderOutboxEventPublisher
    └── authz/                 AuthzProducer (OPA PolicyDecisionPoint)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doména (`StandingOrder`) drží logiku přechodů stavu (`pause`, `resume`, `cancel`, `recordExecution`) a nikdy nevidí JPA, Kafku ani REST DTO.

### Doménový stavový automat

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

  recordExecution → COMPLETED (po překročení endDate) ; FAILED při chybě provedení
```

Invarianty vynucené v agregátu: pozastavit lze jen `ACTIVE`, obnovit jen `PAUSED`, a `CANCELLED`/`COMPLETED` nelze zrušit znovu (`require(...)` strážci).

## Outbox tok

```mermaid
sequenceDiagram
  participant C as Klient
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

  loop každých 5s (@Scheduled, SKIP souběhu)
    D->>DB: listProcessable(batch=25)
    D->>K: publish do openbank.standing-orders.order.event
    D->>DB: markSent(eventId) / markFailed(eventId, error)
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. Doručení je at-least-once; dispatcher obaluje publikaci **resilience stackem** — `@CircuitBreaker` (volume 10, ratio 0.5), `@Retry` (2 retries, jitter), `@Bulkhead` (1), `@Timeout` (3 s) — a plánovač chyby pohltí, aby smyčku nikdy neshodil.

## Klíčové porty

| Port (směr) | Definováno v | Adaptér |
|---|---|---|
| `StandingOrderUseCase` (in) | `application/port/in` | volá `StandingOrderResource` |
| `StandingOrderRepository` (out) | `application/port/out` | `StandingOrderRepositoryImpl` (Hibernate Reactive) |
| `StandingOrderOutboxRepository` (out) | `application/port/out` | `StandingOrderOutboxRepositoryImpl` |
| `StandingOrderOutboxEventPublisher` (out) | `application/port/out` | `KafkaStandingOrderOutboxEventPublisher` |
| `PolicyDecisionPoint` (out) | `openbank-libs` authz | `AuthzProducer` → OPA sidecar |

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.authz.@Authorize` + `PolicyDecisionPoint` | deklarativní autorizace na mutacích (např. `standingOrder.pause`) |
| `libs.authz.OpaSidecarPolicyDecisionPoint` | klient OPA sidecaru (advisory/enforce) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |

## Principy

1. **Hranice agregátu = StandingOrder** — každá operace životního cyklu je atomická nad jedním příkazem.
2. **Nejprve doménové události** — změny stavu vydávají doménové události; infrastruktura je serializuje do outboxu.
3. **Žádná vzdálená volání v TX** — synchronně v rámci request-response, asynchronně přes outbox + Kafka.
4. **Idempotentní vytvoření** — `idempotencyKey` je unikátní v DB; opakované vytvoření vrátí existující příkaz.
5. **Oddělené provádění** — tato služba zaznamenává záměr; navazující služby provádějí platbu.
