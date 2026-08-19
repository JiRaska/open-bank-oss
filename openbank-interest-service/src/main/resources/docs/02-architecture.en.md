# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui / operator]
  sched["scheduler<br/>@Scheduled cron"]
  tax[tax / reporting consumer<br/>pays finanční úřad]
  ledger[ledger-service]
  audit[audit-service]

  int[(interest-service)]:::svc
  db[(PostgreSQL<br/>db: openbank_interest)]
  kafka[(Kafka<br/>openbank.interest.accrual.event)]
  kc[(Keycloak<br/>OIDC)]

  admin -- "POST/GET /api/v1/interest/..." --> int
  sched -- "accrue / capitalize ticks" --> int
  int -- "verify Bearer token" --> kc

  int --> db
  int -- "outbox → publish" --> kafka

  kafka --> tax
  kafka --> ledger
  kafka --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-interest-service (Quarkus 3 LTS, reactive)"
    direction TB
    rest[REST<br/>InterestResource<br/>WithholdingRemittanceResource]
    uc[Application<br/>InterestService<br/>WithholdingRemittanceService]
    dom[Domain<br/>InterestRateConfig / InterestAccrual / InterestCapitalization<br/>WithholdingTaxPolicy / WithholdingRemittancePolicy]
    persist[Persistence<br/>*RepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>InterestOutboxDispatcher<br/>@Scheduled every 5s"]
    kafka[Kafka publisher<br/>KafkaInterestOutboxEventPublisher<br/>+ fault tolerance]
    tax[TaxProfilePort<br/>DefaultTaxProfileProvider]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  uc --> tax
  outbox --> kafka

  persist -.-> db[(PostgreSQL)]
  kafka -.-> broker[(Kafka)]
```

## Hexagonal layers

The package structure reflects **ports-and-adapters** (ADR-0002):

```
com.openbank.interest/
├── domain/                          ◄── core — ZERO framework imports
│   ├── model/                       InterestRateConfig, InterestAccrual, InterestCapitalization,
│   │                                AccrualRequest, AccrualSummary + enums
│   └── tax/                         WithholdingTaxPolicy / WithholdingRemittancePolicy (pure §36/§38d ZDP),
│                                    TaxProfile, WithholdingTax, WithholdingRemittance
│
├── application/                     ◄── use-case orchestration
│   ├── port/in/                     AccrueInterestUseCase, CapitalizeInterestUseCase,
│   │                                GetAccrualsUseCase, ManageRateConfigUseCase, RemitWithholdingUseCase
│   ├── port/out/                    *Repository, TaxProfilePort, InterestEventOutbox
│   └── usecase/                     InterestService, WithholdingRemittanceService
│
└── infrastructure/                  ◄── adapters
    ├── rest/                        JAX-RS resources + DTO mapping
    ├── persistence/                 *Entity (Panache), *RepositoryImpl, InterestMapper
    ├── outbox/                      InterestOutboxDispatcher (@Scheduled)
    ├── kafka/                       KafkaInterestOutboxEventPublisher
    └── tax/                         DefaultTaxProfileProvider
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. The two tax policies (`WithholdingTaxPolicy`, `WithholdingRemittancePolicy`) are pure objects with no infrastructure imports, so the statutory rules and their rounding live in one tested place and cannot drift across call sites.

## Capitalization + withholding flow

```mermaid
sequenceDiagram
  participant C as Client / scheduler
  participant R as InterestResource
  participant S as InterestService
  participant T as TaxProfilePort
  participant DB as PostgreSQL
  participant D as InterestOutboxDispatcher
  participant K as Kafka

  C->>R: POST /capitalize/{accountId}?productId&toDate
  R->>S: capitalize(...)
  S->>DB: find pending accruals
  S->>T: resolve(accountId) → TaxProfile (fail-safe default)
  S->>S: WithholdingTaxPolicy.compute(gross, currency, profile)
  S->>DB: INSERT capitalization (gross/tax/net)
  S->>DB: INSERT withholding_tax (RECORDED)
  S->>DB: INSERT interest_outbox (interest.withholding.recorded.v1, PENDING)
  S->>DB: UPDATE accruals → CAPITALIZED
  R-->>C: 200 (net credited, with the split)

  loop every 5s (SKIP concurrent, replicas=1)
    D->>DB: SELECT processable outbox rows (batch 25)
    D->>K: publishResilient (key = aggregate_id, ce-id/ce-type headers)
    D->>DB: UPDATE outbox → SENT (or FAILED on error)
  end
```

## Monthly remittance flow

```mermaid
sequenceDiagram
  participant C as Operator
  participant R as WithholdingRemittanceResource
  participant S as WithholdingRemittanceService
  participant DB as PostgreSQL
  participant K as Kafka

  C->>R: POST /withholding/remittances?year&month
  R->>S: assembleRemittance(year, month)
  S->>DB: findByPeriod(year, month)
  alt batch already exists (idempotent)
    S-->>R: return existing batch
  else
    S->>DB: findRecordedForPeriod(monthStart, monthEnd)
    S->>S: WithholdingRemittancePolicy.assemble(records, year, month)
    S->>DB: INSERT withholding_remittance (PENDING)
    S->>DB: UPDATE withholding_tax → REMITTED (stamp remittance_id)
    S->>DB: INSERT outbox (interest.withholding.remitted.v1)
  end
  R-->>C: 201 (batch)
```

**Why outbox (ADR-0050):** transactional consistency between the DB write and the Kafka publish. The dispatcher runs on the Vert.x event loop, uses `concurrentExecution = SKIP`, and the Deployment is pinned to `replicas: 1` — exactly one writer claims a row, preserving per-aggregate ordering (partition key = `aggregate_id`). At-least-once delivery; `ce-id` / `idempotency-key` headers let consumers deduplicate.

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata, API + service version) |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | runtime tech-stack snapshot |
| `libs` security plumbing | OIDC integration, secret bootstrap checks |

## Principles

1. **Pure tax policy** — `WithholdingTaxPolicy` and `WithholdingRemittancePolicy` are framework-free; the §36/§38d ZDP rules and statutory rounding live in one tested place.
2. **Fail-safe withholding** — `TaxProfilePort` implementations must never propagate a failure that would skip withholding; they resolve to the fiscally conservative CZ-resident-individual default.
3. **Net credit, recorded liability** — capitalization credits the net amount and records the withholding decision (even zero-tax treatments) for the audit trail.
4. **No remote calls in the write TX** — the event lands with the aggregate change via the outbox; downstream propagation is async via Kafka.
5. **Idempotent remittance** — one batch per `(year, month)`; re-running returns the assembled batch and re-marks nothing.
