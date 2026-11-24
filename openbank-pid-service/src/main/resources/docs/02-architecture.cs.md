# Architektura

## C4 — Kontext systému

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
  pid -- "publikuje události" --> kafka
  pid -. "@Authorize kontrola" .-> opa

  kafka --> kyc
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-pid-service (Quarkus, reaktivní)"
    direction TB
    rest[REST<br/>PartyResource<br/>ExceptionMappers]
    uc[Application<br/>PartyService<br/>+ CaseTransitionEngine]
    dom[Domain<br/>agregát Party<br/>+ doménové události]
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

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters** (ADR-0002):

```
com.openbank.pid/
├── domain/                     ◄── jádro — bez frameworkových závislostí
│   ├── model/                  Party, CoreAttributes, KycAttributes,
│   │                           ExternalId, PartyRelationship, PartyCaseLifecycle, enumy
│   └── event/                  PartyCreated, PartyVerified, KycLevelChanged,
│                               PartyStatusChanged, RelationshipAdded/Terminated,
│                               AddressUpdatedFromRob, case.created/transitioned/evidence.linked
│
├── application/                ◄── orchestrace use case
│   ├── port/in/                CreatePartyUseCase, GetPartyUseCase, UpdatePartyUseCase,
│   │                           ManageRelationshipUseCase + command/query záznamy
│   ├── port/out/               PartyRepositoryPort, PartyEventPublisher
│   └── usecase/                PartyService (implementuje všechny čtyři vstupní porty)
│
└── infrastructure/             ◄── adaptéry
    ├── rest/                   PartyResource (JAX-RS), dto/, ExceptionMappers
    ├── persistence/            PartyRepositoryImpl, entity/
    ├── messaging/              Kafka producenti
    └── authz/                  AuthzProducer (napojení na OPA)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód nikdy nevidí Hibernate, Kafku ani REST DTO. CI vynucuje nulové frameworkové importy v doménové vrstvě.

## Doménový model — agregát Party

`Party` je immutable Kotlin `data class` (viz `domain/model/Party.kt`). Všechny mutace jdou přes `PartyService`, který agregát `.copy(...)`-uje, zvedne `version`, uloží a poté publikuje události. Pozoruhodné chování:

- `hasRole(role)` / `isCustomer()` / `isEmployee()` — odvozeno z aktivních vztahů.
- `externalId(type)` — rozlišení jednoho externího identifikátoru.
- `PartyCaseLifecycle` — vkládá case z `libs.domain.case`; přechody validuje `CaseTransitionEngine`, který vrací `Applied` (nový stav + timeline událost) nebo `Rejected` (→ `InvalidPartyCaseTransitionException` → HTTP 400).

## Tok událostí

`PartyService` volá `PartyEventPublisher` (`KafkaPartyEventPublisher`), který serializuje `{eventType, aggregateId, occurredAt, payload}` a posílá do kanálu `party-events-out` (topic `party.events`), klíčováno přes `aggregateId`.

```mermaid
sequenceDiagram
  participant C as Klient (admin-ui)
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

## Klíčové porty

| Port (rozhraní) | Směr | Adaptér |
|---|---|---|
| `CreatePartyUseCase` / `GetPartyUseCase` / `UpdatePartyUseCase` / `ManageRelationshipUseCase` | vstupní | `PartyResource` → `PartyService` |
| `PartyRepositoryPort` | výstupní | `PartyRepositoryImpl` (Hibernate Reactive / Panache) |
| `PartyEventPublisher` | výstupní | `KafkaPartyEventPublisher` |

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.domain.case.*` | `CaseTransitionEngine`, `CaseId`, `CaseStatus`, `CaseReasonCode`, `CaseType` — životní cyklus PID verifikačního případu |
| `libs.domain.event.DomainEvent` | bázová třída všech party událostí (aggregateId, eventType, version, occurredAt) |
| `libs.authz.@Authorize` | autorizace přes OPA na citlivých mutacích (např. `changeStatus`) |
| `libs.api.error.ApiError` / `ErrorCode` | jednotné chybové tělo |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **tato dokumentace** na `/q/openbank/docs` |

## Principy

1. **Hranice agregátu = Party** — identita, KYC, adresy, kontakty a vztahy jsou jedna konzistenční jednotka; optimistické zamykání přes `version`.
2. **Immutable doména, události při každé změně** — `PartyService` agregát rekonstruuje a vyšle doménovou událost na každý přechod.
3. **KYC/AML uložené, nikoli počítané** — riziková rozhodnutí dělají nadřazené služby; pid-service ukládá výsledek a vyšle `KycLevelChanged`, když se úroveň skutečně změní.
4. **Vysvětlitelný case lifecycle** — verifikační přechody hlídá `CaseTransitionEngine`, který zamítá nelegální přechody už v doménové vrstvě.
5. **Minimalizace PII** — rodné číslo se ukládá jen jako `birth_number_encrypted`; logy musí maskovat osobní data.
