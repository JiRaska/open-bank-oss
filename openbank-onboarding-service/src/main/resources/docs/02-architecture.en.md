# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui cockpit]
  party[party-service]
  kyc[kyc-service]
  sca[sca-service]

  onb[(onboarding-service)]:::svc
  db[(PostgreSQL<br/>openbank_onboarding)]
  kparty[(Kafka<br/>openbank.party.events)]
  kkyc[(Kafka<br/>openbank.kyc.events)]
  ksca[(Kafka<br/>openbank.sca.events)]

  party -- "publishes" --> kparty
  kyc -- "publishes" --> kkyc
  sca -- "publishes" --> ksca

  kparty -- "@Incoming party-events-in" --> onb
  kkyc -- "@Incoming kyc-events-in" --> onb
  ksca -- "@Incoming sca-events-in" --> onb

  admin -- "GET /api/v1/onboarding/*" --> onb
  onb --> db

  classDef svc fill:#dbeafe,stroke:#2563eb
```

Note: the arrows are **one-way ingestion**. onboarding-service never calls party/kyc/sca and never publishes events.

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-onboarding-service (Quarkus 3)"
    direction TB
    rest[REST<br/>OnboardingResource<br/>3 GET endpoints]
    uc[Application<br/>OnboardingProjectionService<br/>implements OnboardingUseCase]
    dom[Domain<br/>OnboardingRecord / OnboardingEvent<br/>FunnelStage.derive]
    consumer["Messaging<br/>OnboardingEventConsumer<br/>3 suspend @Incoming"]
    persist[Persistence<br/>OnboardingRepositoryImpl<br/>Hibernate Reactive / Panache]
  end

  rest --> uc
  consumer --> uc
  uc --> dom
  uc --> persist

  consumer -.-> kafka[(Kafka topics)]
  persist -.-> db[(PostgreSQL)]
```

## Hexagonal layers

The package structure reflects **ports-and-adapters** (ADR-0002):

```
com.openbank.onboarding/
├── domain/                     ◄── core — no framework dependencies
│   └── model/
│       ├── OnboardingRecord    read-model aggregate + PartyStage / KycStage enums
│       ├── FunnelStage         derived stage + pure derive(party, kyc, scaEnrolled)
│       └── OnboardingEvent     sealed inbound-event hierarchy
│
├── application/                ◄── use-case orchestration
│   ├── port/in/
│   │   └── OnboardingUseCase   inbound port (read queries)
│   ├── port/out/
│   │   └── OnboardingRepository outbound port (persistence)
│   └── usecase/
│       └── OnboardingProjectionService  query side + applyEvent projection
│
└── infrastructure/             ◄── adapters
    ├── rest/                   OnboardingResource (JAX-RS, DTO mapping)
    ├── kafka/                  OnboardingEventConsumer (parse + dispatch)
    └── persistence/
        ├── entity/             OnboardingEntity (Panache)
        └── repository/         OnboardingRepositoryImpl
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. The domain layer (notably `FunnelStage.derive`) carries the funnel logic and has zero framework imports, so it is unit-testable in isolation.

## Event-projection flow

There is **no outbox** here — the service consumes events and updates the read-model; it never produces. The flow is consume → parse → project → upsert.

```mermaid
sequenceDiagram
  participant K as Kafka (party/kyc/sca topics)
  participant C as OnboardingEventConsumer
  participant S as OnboardingProjectionService
  participant DB as PostgreSQL

  K->>C: event payload (JSON string)
  C->>C: parse to OnboardingEvent (sealed type)
  alt parse fails / unknown type
    C->>C: log + ACK (poison-pill protection)
  else parsed
    C->>S: applyEvent(event)
    S->>DB: findByPartyId(partyId)
    S->>S: copy(...) + FunnelStage.derive(...)
    S->>DB: upsert(record)
  end
```

**Poison-pill protection:** any parse or projection failure is logged and the message is **acked** (not re-queued). This is correct for a read-model — a single bad event must not wedge the consumer group, and the canonical source of truth (party/kyc/sca) can always be replayed to rebuild the projection. Consumers run on `suspend @Incoming` handlers (same pattern as balance-service's ledger projection consumer), with `auto.offset.reset=earliest` so a fresh deployment seeds from the start of the log.

## Funnel-stage derivation

`FunnelStage.derive(party, kyc, scaEnrolled)` is the one canonical mapping (ADR-0068 §2), evaluated on every projected event:

| Condition | Resulting stage |
|---|---|
| party `SUSPENDED` or `CLOSED` | `BLOCKED` |
| party `ACTIVE` and `scaEnrolled` | `ACTIVE` |
| party `ACTIVE` and not enrolled | `SCA_PENDING` |
| kyc `UNDER_REVIEW` | `KYC_UNDER_REVIEW` |
| kyc `DOCUMENTS_REQUIRED` | `KYC_DOCUMENTS_REQUIRED` | <!-- unreachable: kyc never sets this status (#8535) -->
| kyc `OPEN` or null | `KYC_OPEN` |
| kyc `REJECTED` or `EXPIRED` | `BLOCKED` |
| otherwise | `REGISTERED` |

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.web.ApiVersionResponseFilter` | `X-API-Version` / `X-Service-Version` headers |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |
| build metadata (`BUILD_TIME`, `GIT_COMMIT`) | runtime identification (DORA Art. 9) |

## Principles

1. **Projection, never authority** — the service observes; party/kyc/sca own every state transition.
2. **Pure, tested stage function** — `FunnelStage.derive` lives in the domain and is exhaustively unit-tested.
3. **Idempotent by construction** — projection is upsert-by-`party_id`; replaying an event is safe.
4. **Resilient ingestion** — poison-pill events are acked and logged, never block the consumer group.
5. **Rebuildable** — the read-model can be dropped and re-seeded from the source event log.
