# Architecture

## C4 — System Context

```mermaid
graph LR
  tx[transaction-service]
  fx[fx-service]
  bal[balance-service]
  audit[audit-service]
  admin[admin-ui]

  led[(ledger-service)]:::svc
  db[(PostgreSQL<br/>openbank_ledger<br/>partitioned journal)]
  kafka[(Kafka<br/>openbank.ledger.journal.posted)]

  tx -- "POST /journals<br/>(balanced double entry)" --> led
  led -- "GET ČNB rate" --> fx
  admin -- "GET journals / trial-balance<br/>POST fx-revaluation" --> led

  led --> db
  led -- "outbox → publish" --> kafka

  kafka --> bal
  kafka --> audit
  bal -. "reconciliation reads<br/>GET sub-ledger-balances" .-> led

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-ledger-service (Quarkus 3.33.2, reactive)"
    direction TB
    rest[REST<br/>LedgerResource<br/>FxRevaluationResource]
    uc[Application<br/>LedgerService<br/>FxRevaluationService]
    dom[Domain<br/>JournalEntry / JournalLine / GlAccount<br/>TrialBalance / FxRevaluationPosting<br/>+ domain events]
    persist[Persistence<br/>PanacheJournalRepository<br/>PanacheGlAccountRepository<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>LedgerOutboxDispatcher<br/>@Scheduled every 5s"]
    sched[Schedule<br/>FxRevaluationScheduler<br/>JournalPartitionMaintainer]
    fxcli[FX client<br/>FxServiceClient<br/>FxServiceCnbRateAdapter]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  uc --> fxcli
  sched --> uc

  persist -.-> db[(PostgreSQL)]
  outbox -.-> kafka[(Kafka)]
  fxcli -.-> fxsvc[fx-service]
```

## Hexagonal layers

The directory structure reflects **ports-and-adapters** (ADR-0002):

```
com.openbank.ledger/
├── domain/                    ◄── core — no framework dependencies
│   ├── model/                 JournalEntry, JournalLine, GlAccount, TrialBalance,
│   │                          FxConversionPosting, FxRevaluationPosting
│   └── event/                 JournalPosted, JournalReversed, FxRevalued
│
├── application/               ◄── use-case orchestration
│   ├── port/in/               LedgerPorts (LedgerUseCase), FxRevaluationPorts
│   ├── port/out/              GlAccountRepository, JournalRepository,
│   │                          LedgerOutboxPort, CnbRateProvider
│   └── usecase/               LedgerService, FxRevaluationService
│
└── infrastructure/            ◄── adapters
    ├── rest/                  LedgerResource, FxRevaluationResource, ExceptionMappers
    ├── persistence/           Panache*Repository, JournalEntities, LedgerOutboxEntity
    ├── outbox/                LedgerOutboxDispatcher (scheduled drain)
    ├── messaging/             KafkaLedgerOutboxEventPublisher
    ├── client/                FxServiceClient, FxServiceCnbRateAdapter
    ├── partition/             JournalPartitionMaintainer, HibernatePartitionExecutor
    └── schedule/              FxRevaluationScheduler
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. Domain code never sees Hibernate, Kafka, or REST DTOs. The double-entry invariant lives entirely in `JournalEntry` (`validateBalance()` runs in the aggregate `init`).

## Key ports

| Port (direction) | Adapter | Purpose |
|---|---|---|
| `LedgerUseCase` (in) | `LedgerService` | post/reverse/query journals, trial & sub-ledger balances |
| `FxRevaluationUseCase` (in) | `FxRevaluationService` | daily mark-to-ČNB revaluation |
| `JournalRepository` (out) | `PanacheJournalRepository` | persist/read journal entries + lines |
| `GlAccountRepository` (out) | `PanacheGlAccountRepository` | chart-of-accounts lookups by code/id |
| `LedgerOutboxPort` (out) | `LedgerOutboxRepositoryImpl` | enqueue + claim outbox rows |
| `CnbRateProvider` (out) | `FxServiceCnbRateAdapter` (→ `FxServiceClient`) | statutory ČNB FX fixing |

## Outbox flow

```mermaid
sequenceDiagram
  participant TX as transaction-service
  participant R as LedgerResource
  participant S as LedgerService
  participant DB as PostgreSQL
  participant D as LedgerOutboxDispatcher
  participant K as Kafka

  TX->>R: POST /journals (idempotencyKey, balanced lines)
  R->>S: postJournal(command)
  S->>S: JournalEntry.validateBalance() (per currency)
  S->>DB: BEGIN TX
  S->>DB: check/insert ledger_idempotency
  S->>DB: INSERT journal_entries + journal_lines (POSTED)
  S->>DB: INSERT ledger_outbox (JournalPosted, status=PENDING)
  S->>DB: COMMIT
  R-->>TX: 201 Created (journal id)

  loop @Scheduled every 5s (SKIP overlap, replicas=1)
    D->>DB: claim up to 25 processable rows
    D->>K: publishResilient → openbank.ledger.journal.posted
    D->>DB: markSent / markFailed (bounded → DEAD)
  end
```

**Why outbox (ADR-0050):** the DB write and the Kafka publish must be atomic-ish. Money-path delivery is regulatory-grade: a single in-JVM writer (`concurrentExecution = SKIP`) plus `replicas: 1` guarantee exactly one dispatcher claims a row; per-row failures are isolated and bounded by a DEAD transition. The dispatcher runs fully on the Vert.x event loop (reactive Panache) to avoid worker-thread session errors.

## Partition lifecycle

`journal_entries` is RANGE-partitioned by `entry_date` (one partition per calendar year + a DEFAULT catch-all). The `JournalPartitionMaintainer` rolls the horizon forward (`future-years: 2`) and, in DETACH-only/dry-run mode by default, manages retention (`retention-years: 10`). Every lifecycle action is written to the immutable `partition_lifecycle_audit` table. DROP is destructive and gated behind a deliberate, archived operator flag flip.

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.domain.money.Money` + `CurrencyCode` | per-line and base amounts, currency-safe arithmetic |
| `libs.domain.event.DomainEvent` | base for JournalPosted / JournalReversed / FxRevalued |
| `libs.api.pagination.CursorPage` | cursor-paginated journal listing |
| `libs.security.Roles` | typed role constants for `@RolesAllowed` |
| `libs.web.ServiceInfoResource` | `/api/v1/info` build metadata |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |

## Principles

1. **Aggregate boundary = JournalEntry** — a posting is atomic and balances within each currency before it can exist (`init` invariant).
2. **Immutability + reversal-only correction** — a POSTED entry is never mutated; a mistake is fixed by a balanced reversal that links back via `reversal_of`.
3. **Per-currency balancing (ADR-0025)** — cross-currency events self-balance by routing through per-currency FX position accounts; never a base-currency cross-sum.
4. **Ledger is golden, balance is projection (ADR-0039)** — the ledger owns truth; `balance-service` reconciles against it.
5. **No remote calls inside the write TX** — Kafka publish is async via outbox; ČNB rate fetch happens in the revaluation use case, not in the journal write path.
