# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui / operátor]
  feed[upstream feed<br/>ROLE_API]
  fx[fx-service]

  ana[(anacredit-service)]:::svc
  store[(PostgreSQL<br/>credit_exposures)]
  kc[(Keycloak<br/>OIDC)]

  admin -- "POST /exposures<br/>GET /returns/{date}" --> ana
  feed -- "POST /exposures" --> ana
  fx -. "committedAmountEur<br/>(dodá volající)" .-> feed
  ana --> store
  ana -. "validace bearer tokenu" .-> kc

  classDef svc fill:#dbeafe,stroke:#2563eb
```

Neexistuje **žádná** odchozí integrace: anacredit-service nepublikuje žádné události a nevolá žádnou jinou službu OpenBank. Submisní kanál ČNB je ve v1 mimo rozsah.

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-anacredit-service (Quarkus 3.x, JDK 25)"
    direction TB
    rest[REST<br/>AnaCreditResource<br/>+ DTO]
    uc[Application<br/>AnaCreditService<br/>in/out porty]
    dom[Domain<br/>CreditExposure<br/>EligibilityPolicy<br/>ReturnBuilder / Mapper]
    persist[Persistence<br/>PostgresCreditExposureRepository]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  persist -.-> store[(PostgreSQL<br/>credit_exposures)]
```

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters** (ADR-0002); balíček `domain` má **nula** frameworkových importů:

```
com.openbank.anacredit/
├── domain/                          ◄── jádro — žádné frameworkové závislosti
│   ├── model/                       CreditExposure, CounterpartyType, InstrumentType
│   ├── eligibility/                 AnaCreditEligibilityPolicy, Eligibility (brána rozsah + práh)
│   └── report/                      AnaCreditCreditRecord, ExclusionNote, AnaCreditReturn,
│                                    AnaCreditReturnBuilder, AnaCreditMapper
│
├── application/                     ◄── orchestrace use-case
│   ├── port/in/                     RegisterExposureUseCase, ListExposuresUseCase,
│   │                                BuildAnaCreditReturnUseCase, RegisterExposureCommand
│   ├── port/out/                    CreditExposureRepository (odchozí port)
│   └── AnaCreditService             implementuje tři vstupní use-case
│
└── infrastructure/                  ◄── adaptéry
    ├── rest/                        AnaCreditResource (JAX-RS), dto/AnaCreditDtos
    └── persistence/                 CreditExposureEntity (Panache), PostgresCreditExposureRepository
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód nikdy nevidí JAX-RS, Jackson ani CDI.

## Porty

| Port | Směr | Definován v | Adaptér |
|---|---|---|---|
| `RegisterExposureUseCase` | vstupní | `application/port/in` | `AnaCreditService` |
| `ListExposuresUseCase` | vstupní | `application/port/in` | `AnaCreditService` |
| `BuildAnaCreditReturnUseCase` | vstupní | `application/port/in` | `AnaCreditService` |
| `CreditExposureRepository` (`upsert`/`findById`/`listAll`, vše `suspend`) | odchozí | `application/port/out` | `PostgresCreditExposureRepository` |

Port udržel výměnu z in-memory `ConcurrentHashMap` (v1) na reaktivní Panache adaptér nad
`anacredit_schema` (ADR-0037 v2) mechanickou: jediný nový odchozí adaptér; doménová a aplikační
vrstva se nezměnily.

## Render pipeline (žádný outbox — derive-only)

Na rozdíl od money-path služeb anacredit-service nemá **žádný transakční outbox** a **žádný tok událostí**. Výkaz se počítá synchronně, na vyžádání, ze stávající množiny expozic:

```mermaid
sequenceDiagram
  participant C as Klient (operátor / auditor)
  participant R as AnaCreditResource
  participant S as AnaCreditService
  participant ST as CreditExposureRepository (PostgreSQL)
  participant B as AnaCreditReturnBuilder (čistá doména)

  C->>R: GET /api/v1/anacredit/returns/{referenceDate}
  R->>S: build(referenceDate)
  S->>ST: listAll()
  ST-->>S: všechny expozice
  S->>B: build(exposures, referenceDate)
  Note over B: 1. agregace celkového committedAmountEur na dlužníka<br/>2. posouzení každého nástroje (rozsah + práh €25k)<br/>3. mapování reportovatelných → CreditRecord<br/>4. ExclusionNote pro každý vyřazený nástroj
  B-->>S: AnaCreditReturn (records + exclusions)
  S-->>R: AnaCreditReturn
  R-->>C: 200 OK (AnaCreditReturnResponse)
```

Práh €25 000 je test *na dlužníka*, takže `AnaCreditReturnBuilder` nejprve sečte celkový `committedAmountEur` dlužníka napříč všemi jeho nástroji a teprve poté aplikuje `AnaCreditEligibilityPolicy.assess(...)` per nástroj.

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | runtime snapshot tech stacku |

## Principy

1. **Derive-only** — služba je projekce; nevlastní žádný peněžní stav a neemituje události, proto je mimo money-path bránu (ADR-0030).
2. **Čistá doménová pravidla** — rozsah + materialita žijí v `AnaCreditEligibilityPolicy`, frameworkově nezávislém objektu s deterministickými, auditně orientovanými důvodovými kódy.
3. **Vysvětli každé vyřazení** — reportovatelný nástroj se stane `CreditRecord`, vyřazený `ExclusionNote`. Nic nemizí tiše.
4. **Reportují nativní částky; EUR jen brání** — řádky datového souboru nesou částky v nativní měně; `committedAmountEur` se používá pouze pro práh.
5. **Úložiště je adaptér** — PostgreSQL store je implementační detail za `CreditExposureRepository`; byl vyměněn za původní in-memory store bez zásahu do jádra.
