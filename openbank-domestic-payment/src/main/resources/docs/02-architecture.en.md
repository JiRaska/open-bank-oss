# Architecture

## C4 — System Context

```mermaid
graph LR
  ch[channels / operators]
  admin[admin-ui]
  sanc[sanctions-service]
  aml[aml-service]
  clr[clearing-service]
  led[ledger-service]
  audit[audit-service]
  notif[notification]

  dp[(domestic-payment-service)]:::svc
  db[(PostgreSQL<br/>openbank_domestic_payments)]
  kafka[(Kafka<br/>openbank.domestic.payment.events)]
  redis[(Valkey<br/>four-eyes approvals)]

  ch -- "POST/GET/PATCH /domestic-payments" --> dp
  admin -- "read + manual transitions" --> dp
  dp -- "screen debtor+creditor (sync)" --> sanc
  dp -- "open AML case (hit/review/outage)" --> aml

  dp --> db
  dp -- "outbox → publish" --> kafka
  dp -- "approval workflow state" --> redis

  kafka --> clr
  kafka --> led
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-domestic-payment (Quarkus, reactive)"
    direction TB
    rest[REST<br/>DomesticPaymentResource<br/>ExceptionMappers]
    uc[Application<br/>DomesticPaymentService]
    dom[Domain<br/>DomesticPayment + state machine<br/>ScreeningPolicy + events]
    persist[Persistence<br/>DomesticPaymentRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox[Outbox<br/>DomesticPaymentOutboxDispatcher<br/>polls every 5s, batch 25]
    kafkaPub[Kafka publisher<br/>KafkaDomesticPaymentEventPublisher]
    idem[Idempotency<br/>normalized request fingerprint]
    sancCli[Sanctions client<br/>SanctionsScreeningAdapter]
    amlCli[AML client<br/>AmlCaseAdapter]
  end

  rest --> uc
  uc --> dom
  uc --> idem
  uc --> persist
  idem --> persist
  uc --> sancCli
  uc --> amlCli
  persist -.-> db[(PostgreSQL)]
  outbox --> kafkaPub
  outbox -.-> db
  kafkaPub -.-> kafka[(Kafka)]
  sancCli -.-> sanc[(sanctions-service)]
  amlCli -.-> aml[(aml-service)]
```

## Hexagonal layers

The package structure reflects **ports-and-adapters** (ADR-0002):

```
com.openbank.domestic/
├── domain/                    ◄── core — no framework dependencies
│   ├── model/                 DomesticPayment, status / priority / scope / reject enums,
│   │                          transitionTo() + canTransitionTo() (the state machine)
│   ├── event/                 DomesticPaymentCreatedEvent, DomesticPaymentStatusChangedEvent
│   └── screening/             ScreeningPolicy (pure decision), ScreeningResult, ScreeningDecision
│
├── application/               ◄── use-case orchestration
│   ├── port/in/               DomesticPaymentUseCase + commands / queries
│   ├── port/out/              DomesticPaymentRepository, OutboxRepository, EventPublisher,
│   │                          SanctionsScreeningPort, AmlCasePort
│   └── usecase/               DomesticPaymentService (create → persist → screen → transition)
│
└── infrastructure/            ◄── adapters
    ├── rest/                  DomesticPaymentResource, DTOs, ExceptionMappers
    ├── persistence/           repository impls, JPA entities, mappers
    ├── outbox/                DomesticPaymentOutboxDispatcher (scheduled, fault-tolerant)
    ├── kafka/                 KafkaDomesticPaymentEventPublisher
    ├── approval/              ApprovalConfig (Redis-backed four-eyes workflow only)
    ├── client/               SanctionsServiceClient + adapter, AmlServiceClient + adapter
    └── authz/                AuthzProducer (OPA, ADR-0034)
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. The domain never imports the sanctions-service package — `ScreeningMatchStatus` is a local mirror enum; the adapter maps the remote status onto it.

## The screening gate (ADR-0032)

Screening is the first processing step and runs synchronously, **after** the `RECEIVED` row is durably persisted so the payment is never lost if screening then fails:

```mermaid
sequenceDiagram
  participant C as Client
  participant R as DomesticPaymentResource
  participant S as DomesticPaymentService
  participant DB as PostgreSQL
  participant Sanc as sanctions-service
  participant Aml as aml-service

  C->>R: POST /domestic-payments (Idempotency-Key)
  R->>S: createPayment(cmd)
  S->>DB: INSERT payment (RECEIVED) + outbox (created) [1 TX]
  S->>Sanc: screen(debtorName) + screen(creditorName)
  alt CLEAR
    S->>DB: transition → VALIDATED + outbox [1 TX]
  else REVIEW (potential hit ≤ threshold)
    S->>Aml: open case (HIGH, AML_HOLD)
    Note over S: stays RECEIVED for human decision
  else BLOCK (HIT / ESCALATED / potential > 0.85)
    S->>Aml: open case (CRITICAL, SANCTIONS_HIT)
    S->>DB: transition → REJECTED (SANCTIONS_HIT) + outbox [1 TX]
  else screening unavailable
    S->>Aml: open case (MEDIUM, SCREENING_UNAVAILABLE)
    Note over S: stays RECEIVED (fail-closed)
  end
  R-->>C: 201 Created (final status in body)
```

`ScreeningPolicy.decide()` is a pure function: **BLOCK** dominates **REVIEW** dominates **CLEAR**. BLOCK = any `HIT`/`ESCALATED` or a `POTENTIAL_HIT` strictly above `POTENTIAL_HIT_BLOCK_THRESHOLD` (0.85); REVIEW = any other `POTENTIAL_HIT`; CLEAR = `CLEAR`/`WHITELISTED` or empty. Opening the AML case is best-effort — a case-store outage must not flip the screening verdict.

## Outbox flow

```mermaid
sequenceDiagram
  participant S as DomesticPaymentService
  participant DB as PostgreSQL
  participant D as DomesticPaymentOutboxDispatcher
  participant K as Kafka

  S->>DB: INSERT payment + INSERT outbox(status=PENDING) [same TX]
  loop @Scheduled every 5s (batch 25, SKIP concurrent)
    D->>DB: listProcessable(25)  (PENDING, oldest first)
    D->>K: publishWithResilience(payload)  (CircuitBreaker+Retry+Timeout+Bulkhead)
    alt published
      D->>DB: markSent(eventId)
    else failed
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Why outbox:** transactional consistency between the DB write and the Kafka publish. At-least-once delivery; consumers must be idempotent. The publish call is wrapped in MicroProfile fault-tolerance (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`).

## Key ports (application/port/out)

| Port | Responsibility |
|---|---|
| `DomesticPaymentRepository` | persist/find/list payments; `save`/`update` take the outbox message so the row and event commit atomically |
| `DomesticPaymentOutboxRepository` | drain the outbox — `listProcessable`, `markSent`, `markFailed` |
| `DomesticPaymentEventPublisher` | serialize `created`/`status-changed` payloads at write time; `publish` is the transport call at drain time |
| `SanctionsScreeningPort` | screen a name; throws `ScreeningUnavailableException` when the sanctions service is unreachable (→ fail-closed) |
| `AmlCasePort` | open an AML case (risk level + alert code + matched entity) |

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.persistence.outbox` | outbox entity/repository conventions |
| `libs.api.error.ApiError` / `ErrorCode` | unified error envelope (404 NOT_FOUND, 409 CONFLICT) |
| `libs.authz.@Authorize` | OPA-backed authorization on status transition (ADR-0034) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` build metadata |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |

## Principles

1. **Aggregate boundary = DomesticPayment** — the status state machine (`transitionTo`/`canTransitionTo`) is the only legal mutation path.
2. **Persist before screen** — the `RECEIVED` row + outbox are committed before the synchronous screening call, so a payment is never lost.
3. **Fail closed** — a sanctions-service outage holds the payment in `RECEIVED`; it is never auto-released.
4. **No remote call inside the persistence transaction** — screening and AML-case calls happen between transactions.
5. **Durable, actor-bound idempotency** — `Idempotency-Key` is mandatory; Postgres stores its normalized request fingerprint atomically with the payment/outbox, exact replays return that row, and mismatches fail with 409.
