# Architektura

Hexagonální architektura per [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md).

## C4 — Systémový kontext

```mermaid
graph LR
  pay[platební služby / operátoři]
  gw[SWIFT brána / protistrana]
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
  swift -. "idempotence/cache" .-> redis
  swift -. "authz rozhodnutí" .-> opa

  kafka --> txn
  kafka --> aml
  kafka --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-swift-service (Quarkus 3.x, JDK 25)"
    direction TB
    rest[REST<br/>SwiftResource<br/>+ SwiftDtos]
    uc[Application<br/>SwiftService : SwiftUseCase]
    dom[Domain<br/>SwiftMessage + enums<br/>validate]
    persist[Persistence<br/>SwiftRepositoryImpl<br/>Hibernate Reactive Panache]
    outbox["Outbox<br/>SwiftOutboxDispatcher<br/>@Scheduled každých 5s"]
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

## Hexagonální vrstvy

Struktura balíčků (`com.openbank.swift`) odráží **ports-and-adapters**:

```
com.openbank.swift/
├── domain/
│   └── model/                 SwiftMessage, SwiftMessageType,
│                              SwiftStatus, SwiftPriority + validate()
│
├── application/               ◄── orchestrace use case
│   ├── port/in/               SendSwiftCommand, SwiftUseCase (vstupní port)
│   ├── port/out/              SwiftRepository, SwiftOutboxRepository,
│   │                          SwiftOutboxEventPublisher (výstupní porty)
│   └── usecase/               SwiftService (implementuje SwiftUseCase)
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  SwiftResource (JAX-RS), dto/SwiftDtos
    ├── persistence/           SwiftRepositoryImpl, SwiftOutboxRepositoryImpl,
    │                          entity/, mapper/
    ├── outbox/                SwiftOutboxDispatcher (@Scheduled)
    ├── kafka/                 KafkaSwiftOutboxEventPublisher
    └── authz/                 AuthzProducer (OPA PDP bean)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód (agregát `SwiftMessage` a jeho `validate()`) nemá žádné framework importy.

## Klíčové porty

| Port | Směr | Definováno v | Adaptér |
|---|---|---|---|
| `SwiftUseCase` | vstupní | `application/port/in` | `SwiftService` |
| `SwiftRepository` | výstupní | `application/port/out` | `SwiftRepositoryImpl` (Panache) |
| `SwiftOutboxRepository` | výstupní | `application/port/out` | `SwiftOutboxRepositoryImpl` |
| `SwiftOutboxEventPublisher` | výstupní | `application/port/out` | `KafkaSwiftOutboxEventPublisher` |
| `PolicyDecisionPoint` | výstupní | `libs.authz` | `OpaSidecarPolicyDecisionPoint` přes `AuthzProducer` |

## Outbox → Kafka tok

```mermaid
sequenceDiagram
  participant C as Klient
  participant R as SwiftResource
  participant S as SwiftService
  participant DB as PostgreSQL
  participant D as SwiftOutboxDispatcher
  participant K as Kafka

  C->>R: POST /api/v1/swift (idempotencyKey)
  R->>S: send(cmd)
  S->>DB: findByIdempotencyKey → pokud existuje, vrať stávající
  S->>S: validate() (BIC, ref, částka, charge code)
  S->>DB: INSERT swift_messages (status=VALIDATED)
  R-->>C: 201 Created

  loop @Scheduled každých 5s (SKIP pokud běží)
    D->>DB: listProcessable(25) z swift_outbox (status=PENDING)
    D->>K: publishWithResilience(payload) → topic openbank.payments.swift.event
    D->>DB: markSent(eventId) / markFailed(eventId, error)
  end
```

**Resilience při dispatchi** (`SwiftOutboxDispatcher.publishWithResilience`, SmallRye Fault Tolerance): `@Bulkhead(1)`, `@CircuitBreaker(volume=10, ratio=0.5, delay=5s, success=2)`, `@Retry(max=2, delay=200ms, jitter=100ms)`, `@Timeout(3000ms)`. Scheduler zachytí a spolkne výjimky, aby nikdy nespadl; selhání per-event jsou zaznamenána přes `markFailed` s `last_error` a `attempt_count`.

> **Poznámka k implementaci (na základě kódu):** cesta `SwiftService.send` persistuje agregát, ale aktuálně nevkládá řádek do `swift_outbox`. Outbox repository, dispatcher i Kafka publisher jsou plně implementované; chybí zápis na straně use case, který zařadí doménovou událost (TBD / follow-up).

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.authz.Authorize` | `@Authorize(action="swift.acknowledge", resource="#id")` na ack |
| `libs.authz.PolicyDecisionPoint` / `OpaSidecarPolicyDecisionPoint` | OPA sidecar rozhodnutí, produkováno přes `AuthzProducer` |
| service-info / docs plumbing | `/api/v1/info`, `/q/openbank/docs` (tato dokumentace) |

## Principy

1. **Hranice agregátu = SwiftMessage** — každý create/ack/reject je atomický přechod.
2. **Idempotentní submit** — `idempotencyKey` deduplikováno v use case a přes DB `UNIQUE` constraint.
3. **Transakční outbox** — DB zápis a Kafka publish jsou oddělené; at-least-once doručení, idempotentní konzumenti.
4. **Autorizace na edge** — OPA-gated akce (`@Authorize`), výchozí advisory, vynutitelné přes `AUTHZ_ENFORCE`.
5. **Money-path disciplína** — povrch vysokohodnotových wire; autenticita zprávy je dominantní kontrola (viz [06 — Compliance](./06-compliance.md) a threat model).
