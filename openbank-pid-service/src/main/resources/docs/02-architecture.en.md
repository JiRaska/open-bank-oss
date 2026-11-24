# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui]
  onb[onboarding / IdP]
  acc[account-service]
  kyc[kyc / aml / sanctions]
  audit[audit-service]
  notif[notification]

  pid[(pid-service)]:::svc
  db[(PostgreSQL<br/>db: openbank_pid)]
  kafka[(Kafka<br/>party.events)]
  opa[(OPA sidecar<br/>advisory)]

  admin -- "POST/GET/PATCH /parties" --> pid
  onb -- "POST /sync/bankid" --> pid
  acc -. "GET /parties/{id}, by-external-id" .-> pid
  kyc -- "PUT /kyc (push back)" --> pid

  pid --> db
  pid -- "publish events" --> kafka
  pid -. "@Authorize check" .-> opa

  kafka --> kyc
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-pid-service (Quarkus, reactive)"
    direction TB
    rest[REST<br/>PartyResource<br/>ExceptionMappers]
    uc[Application<br/>PartyService<br/>+ CaseTransitionEngine]
    dom[Domain<br/>Party aggregate<br/>+ domain events]
    persist[Persistence<br/>PartyRepositoryImpl<br/>Hibernate Reactive / Panache]
    msg[Messaging<br/>KafkaPartyEventPublisher]
    authz["Authz<br/>AuthzProducer + @Authorize"]
  end

  rest --> uc
  rest --> authz
  uc --> dom
  uc --> persist
  uc --> msg

  persist -.-> db[(PostgreSQL)]
  msg -.-> kafka[(Kafka)]
  authz -.-> opa[(OPA)]
```

## Hexagonal layers

The package structure reflects **ports-and-adapters** (ADR-0002):

```
com.openbank.pid/
├── domain/                     ◄── core — no framework dependencies
│   ├── model/                  Party, CoreAttributes, KycAttributes,
│   │                           ExternalId, PartyRelationship, PartyCaseLifecycle, enums
│   └── event/                  PartyCreated, PartyVerified, KycLevelChanged,
│                               PartyStatusChanged, RelationshipAdded/Terminated,
│                               AddressUpdatedFromRob, case.created/transitioned/evidence.linked
│
├── application/                ◄── use-case orchestration
│   ├── port/in/                CreatePartyUseCase, GetPartyUseCase, UpdatePartyUseCase,
│   │                           ManageRelationshipUseCase + command/query records
│   ├── port/out/               PartyRepositoryPort, PartyEventPublisher
│   └── usecase/                PartyService (implements all four inbound ports)
│
└── infrastructure/             ◄── adapters
    ├── rest/                   PartyResource (JAX-RS), dto/, ExceptionMappers
    ├── persistence/            PartyRepositoryImpl, entity/
    ├── messaging/              Kafka producers
    └── authz/                  AuthzProducer (OPA wiring)
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. Domain code never sees Hibernate, Kafka, or REST DTOs. CI enforces zero framework imports in the domain layer.

## Domain model — the Party aggregate

`Party` is an immutable Kotlin `data class` (see `domain/model/Party.kt`). All mutations go through `PartyService`, which `.copy(...)`-es the aggregate, bumps `version`, persists, then publishes events. Notable behaviour:

- `hasRole(role)` / `isCustomer()` / `isEmployee()` — derived from active relationships.
- `externalId(type)` — resolve a single external identifier.
- `PartyCaseLifecycle` — embeds a `libs.domain.case` case; transitions are validated by `CaseTransitionEngine` which returns `Applied` (new status + timeline event) or `Rejected` (→ `InvalidPartyCaseTransitionException` → HTTP 400).

## Event flow

`PartyService` calls `PartyEventPublisher` (`KafkaPartyEventPublisher`), which serializes `{eventType, aggregateId, occurredAt, payload}` and sends to channel `party-events-out` (topic `party.events`), keyed by `aggregateId`.

```mermaid
sequenceDiagram
  participant C as Client (admin-ui)
  participant R as PartyResource
  participant S as PartyService
  participant DB as PostgreSQL
  participant K as Kafka (party.events)

  C->>R: POST /api/v1/parties
  R->>S: createParty(cmd)
  S->>DB: INSERT party + relationship
  S->>K: publish PartyCreated / case.created / RelationshipAdded
  R-->>C: 201 Created + PartyResponse
```

## Key ports

| Port (interface) | Direction | Adapter |
|---|---|---|
| `CreatePartyUseCase` / `GetPartyUseCase` / `UpdatePartyUseCase` / `ManageRelationshipUseCase` | inbound | `PartyResource` → `PartyService` |
| `PartyRepositoryPort` | outbound | `PartyRepositoryImpl` (Hibernate Reactive / Panache) |
| `PartyEventPublisher` | outbound | `KafkaPartyEventPublisher` |

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.domain.case.*` | `CaseTransitionEngine`, `CaseId`, `CaseStatus`, `CaseReasonCode`, `CaseType` — PID verification case lifecycle |
| `libs.domain.event.DomainEvent` | base class for all party events (aggregateId, eventType, version, occurredAt) |
| `libs.authz.@Authorize` | OPA-backed authorization on sensitive mutations (e.g. `changeStatus`) |
| `libs.api.error.ApiError` / `ErrorCode` | uniform error body |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **this documentation** at `/q/openbank/docs` |

## Principles

1. **Aggregate boundary = Party** — identity, KYC, addresses, contacts, and relationships are one consistency unit; optimistic locking via `version`.
2. **Immutable domain, events on every change** — `PartyService` recreates the aggregate and emits a domain event per transition.
3. **Stored, not computed, KYC/AML** — risk decisions are made upstream; pid-service persists the result and emits `KycLevelChanged` when the level actually changes.
4. **Explainable case lifecycle** — verification transitions are gated by `CaseTransitionEngine`, rejecting illegal moves at the domain layer.
5. **PII minimisation** — birth number is stored only as `birth_number_encrypted`; logs must mask personal data.
