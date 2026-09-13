# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui / operátor]
  sched["scheduler<br/>@Scheduled cron"]
  tax[daňový/reporting konzument<br/>platí finančnímu úřadu]
  ledger[ledger-service]
  audit[audit-service]

  int[(interest-service)]:::svc
  db[(PostgreSQL<br/>db: openbank_interest)]
  kafka[(Kafka<br/>openbank.interest.accrual.event)]
  kc[(Keycloak<br/>OIDC)]

  admin -- "POST/GET /api/v1/interest/..." --> int
  sched -- "accrue / capitalize ticky" --> int
  int -- "ověř Bearer token" --> kc

  int --> db
  int -- "outbox → publish" --> kafka

  kafka --> tax
  kafka --> ledger
  kafka --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-interest-service (Quarkus 3 LTS, reaktivní)"
    direction TB
    rest[REST<br/>InterestResource<br/>WithholdingRemittanceResource]
    uc[Application<br/>InterestService<br/>WithholdingRemittanceService]
    dom[Domain<br/>InterestRateConfig / InterestAccrual / InterestCapitalization<br/>WithholdingTaxPolicy / WithholdingRemittancePolicy]
    persist[Persistence<br/>*RepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>InterestOutboxDispatcher<br/>@Scheduled každých 5s"]
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

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters** (ADR-0002):

```
com.openbank.interest/
├── domain/                          ◄── jádro — NULOVÉ frameworkové importy
│   ├── model/                       InterestRateConfig, InterestAccrual, InterestCapitalization,
│   │                                AccrualRequest, AccrualSummary + enumy
│   └── tax/                         WithholdingTaxPolicy / WithholdingRemittancePolicy (čisté §36/§38d ZDP),
│                                    TaxProfile, WithholdingTax, WithholdingRemittance
│
├── application/                     ◄── orchestrace use-casů
│   ├── port/in/                     AccrueInterestUseCase, CapitalizeInterestUseCase,
│   │                                GetAccrualsUseCase, ManageRateConfigUseCase, RemitWithholdingUseCase
│   ├── port/out/                    *Repository, TaxProfilePort, InterestEventOutbox
│   └── usecase/                     InterestService, WithholdingRemittanceService
│
└── infrastructure/                  ◄── adaptéry
    ├── rest/                        JAX-RS resources + mapování DTO
    ├── persistence/                 *Entity (Panache), *RepositoryImpl, InterestMapper
    ├── outbox/                      InterestOutboxDispatcher (@Scheduled)
    ├── kafka/                       KafkaInterestOutboxEventPublisher
    └── tax/                         DefaultTaxProfileProvider
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Obě daňové policy (`WithholdingTaxPolicy`, `WithholdingRemittancePolicy`) jsou čisté objekty bez frameworkových importů, takže zákonná pravidla a jejich zaokrouhlování žijí na jednom testovaném místě a nemohou driftovat mezi místy volání.

## Tok kapitalizace + srážky

```mermaid
sequenceDiagram
  participant C as Klient / scheduler
  participant R as InterestResource
  participant S as InterestService
  participant T as TaxProfilePort
  participant DB as PostgreSQL
  participant D as InterestOutboxDispatcher
  participant K as Kafka

  C->>R: POST /capitalize/{accountId}?productId&toDate
  R->>S: capitalize(...)
  S->>DB: najdi nekapitalizované accrualy
  S->>T: resolve(accountId) → TaxProfile (fail-safe default)
  S->>S: WithholdingTaxPolicy.compute(brutto, currency, profile)
  S->>DB: INSERT kapitalizace (brutto/daň/netto)
  S->>DB: INSERT withholding_tax (RECORDED)
  S->>DB: INSERT interest_outbox (interest.withholding.recorded.v1, PENDING)
  S->>DB: UPDATE accrualy → CAPITALIZED
  R-->>C: 200 (připsáno netto, s rozpadem)

  loop každých 5s (SKIP concurrent, replicas=1)
    D->>DB: SELECT zpracovatelné outbox řádky (batch 25)
    D->>K: publishResilient (key = aggregate_id, hlavičky ce-id/ce-type)
    D->>DB: UPDATE outbox → SENT (nebo FAILED při chybě)
  end
```

## Tok měsíčního odvodu

```mermaid
sequenceDiagram
  participant C as Operátor
  participant R as WithholdingRemittanceResource
  participant S as WithholdingRemittanceService
  participant DB as PostgreSQL
  participant K as Kafka

  C->>R: POST /withholding/remittances?year&month
  R->>S: assembleRemittance(year, month)
  S->>DB: findByPeriod(year, month)
  alt dávka už existuje (idempotentní)
    S-->>R: vrať existující dávku
  else
    S->>DB: findRecordedForPeriod(začátekMěsíce, konecMěsíce)
    S->>S: WithholdingRemittancePolicy.assemble(records, year, month)
    S->>DB: INSERT withholding_remittance (PENDING)
    S->>DB: UPDATE withholding_tax → REMITTED (orazítkuj remittance_id)
    S->>DB: INSERT outbox (interest.withholding.remitted.v1)
  end
  R-->>C: 201 (dávka)
```

**Proč outbox (ADR-0050):** transakční konzistence mezi DB zápisem a publikací do Kafky. Dispatcher běží na Vert.x event loop, používá `concurrentExecution = SKIP` a Deployment je připnut na `replicas: 1` — přesně jeden writer si nárokuje řádek, čímž zachová pořadí v rámci agregátu (partition key = `aggregate_id`). Doručení at-least-once; hlavičky `ce-id` / `idempotency-key` umožní konzumentům deduplikaci.

## Komponenty z `openbank-libs`

| Modul | Využití zde |
|---|---|
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata, verze API + služby) |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | runtime snímek tech stacku |
| `libs` security infrastruktura | integrace OIDC, kontrola bootstrap tajemství |

## Principy

1. **Čistá daňová policy** — `WithholdingTaxPolicy` a `WithholdingRemittancePolicy` jsou bez frameworku; pravidla §36/§38d ZDP a zákonné zaokrouhlování žijí na jednom testovaném místě.
2. **Fail-safe srážka** — implementace `TaxProfilePort` nesmí nikdy propagovat selhání, které by srážku přeskočilo; resolvují na fiskálně konzervativní výchozí profil CZ rezidenta fyzické osoby.
3. **Netto kredit, zaznamenaná povinnost** — kapitalizace připisuje netto a zaznamenává rozhodnutí o srážce (i nulové zdanění) pro audit.
4. **Žádná vzdálená volání v zápisové TX** — událost přistává spolu se změnou agregátu přes outbox; downstream propagace je asynchronní přes Kafku.
5. **Idempotentní odvod** — jedna dávka na `(year, month)`; opakované spuštění vrátí sestavenou dávku a nic znovu neoznačí.
