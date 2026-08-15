# Architektura

Služba následuje hexagonální (ports-and-adapters) uspořádání vyžadované [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md).

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui]
  kyc[kyc-service]
  aml[aml-service]
  acc[account-service]
  pid[pid-service]
  audit[audit-service]

  party[(party-service)]:::svc
  db[(PostgreSQL<br/>db: openbank_parties)]
  kafkaOut[(Kafka<br/>openbank.party.events)]
  kafkaKyc[(Kafka<br/>openbank.kyc.events)]
  kafkaAml[(Kafka<br/>openbank.aml.events)]

  admin -- "POST/GET/PATCH /parties" --> party
  kyc -- "PUT /kyc-status" --> party
  acc -. "GET /parties/{id} (vlastník)" .-> party

  kyc -- "emituje" --> kafkaKyc
  aml -- "emituje" --> kafkaAml
  kafkaKyc -- "konzumováno" --> party
  kafkaAml -- "konzumováno" --> party

  party --> db
  party -- "outbox → publish" --> kafkaOut
  kafkaOut --> acc
  kafkaOut --> audit
  pid -. "dokumenty" .-> party

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-party-service (Quarkus)"
    direction TB
    rest[REST<br/>PartyResource<br/>ExceptionMappers]
    uc[Application<br/>PartyService<br/>PartyUseCase port]
    dom[Domain<br/>Party / PartyDocument<br/>PartyStatus / KycStatus / AmlStatus]
    persist[Persistence<br/>PartyRepositoryImpl<br/>PartyOutboxRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox[Outbox<br/>PartyOutboxDispatcher<br/>pollne každých 5s]
    consumer[Kafka in<br/>KycAmlEventConsumer]
    producer[Kafka out<br/>KafkaPartyOutboxEventPublisher]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> producer
  consumer --> uc
  outbox --> producer

  persist -.-> db[(PostgreSQL)]
  producer -.-> kafkaOut[(openbank.party.events)]
  consumer -.-> kafkaIn[(openbank.kyc/aml.events)]
```

## Hexagonální vrstvy

```
com.openbank.party/
├── domain/                          ◄── jádro — žádné framework závislosti
│   └── model/                       Party, PartyDocument, Address,
│                                    PartyType, PartyStatus, KycStatus, AmlStatus
│
├── application/                     ◄── orchestrace use-case
│   ├── port/in/                     PartyPort (PartyUseCase, příkazy, dotazy)
│   ├── port/out/                    PartyRepository, PartyDocumentRepository,
│   │                                PartyOutbox* porty
│   └── usecase/                     PartyService (createParty, updateParty,
│                                    addDocument, updateKycStatus, updateAmlStatus,
│                                    searchParties, eraseParty, deriveStatus brána)
│
└── infrastructure/                  ◄── adaptéry
    ├── rest/                        PartyResource (JAX-RS), ExceptionMappers
    ├── persistence/entity/          PartyEntity, PartyDocumentEntity, PartyOutboxEntity
    ├── persistence/repository/      *RepositoryImpl (Panache reactive)
    ├── outbox/                      PartyOutboxDispatcher (@Scheduled)
    ├── kafka/                       KafkaPartyOutboxEventPublisher, KycAmlEventConsumer
    ├── authz/                       AuthzProducer (OPA, ADR-0034)
    └── flags/                       FlagdProducer (OpenFeature, ADR-0067)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód nikdy neimportuje JPA, Kafka ani JAX-RS.

## Klíčové porty

| Port (out) | Adaptér | Účel |
|---|---|---|
| `PartyRepository` | `PartyRepositoryImpl` | persist/find/search/anonymize party |
| `PartyDocumentRepository` | (Panache) | persist/list dokumentů party |
| `PartyOutboxRepository` | `PartyOutboxRepositoryImpl` | životní cyklus outbox řádku |
| `PartyOutboxEventPublisher` | `KafkaPartyOutboxEventPublisher` | emise uloženého outbox payloadu |

| Port (in) | Adaptér |
|---|---|
| `PartyUseCase` | `PartyResource` (REST), `KycAmlEventConsumer` (Kafka) |

## Outbox tok

```mermaid
sequenceDiagram
  participant C as Klient
  participant R as PartyResource
  participant S as PartyService
  participant DB as PostgreSQL
  participant D as PartyOutboxDispatcher
  participant K as Kafka

  C->>R: POST /parties (Idempotency-Key)
  R->>S: createParty(...)
  S->>DB: BEGIN TX
  S->>DB: INSERT INTO parties
  S->>DB: INSERT INTO party_outbox (PARTY_CREATED, status=PENDING)
  S->>DB: COMMIT
  R-->>C: 201 Created

  loop každých 5s (SKIP pokud běží)
    D->>DB: listProcessable(LIMIT 25)
    D->>K: publishWithResilience → openbank.party.events
    Note over D: @CircuitBreaker + @Retry(2) + @Bulkhead + @Timeout(3s)
    alt úspěch
      D->>DB: markSent(eventId)
    else selhání
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. At-least-once doručení; dispatcher je fault-tolerantní (circuit breaker, omezený retry, bulkhead, timeout) a `@Scheduled` běh se přeskočí, dokud dávka stále probíhá.

## Zpracování příchozích eventů — dvouklíčová aktivační brána

`KycAmlEventConsumer` konzumuje dva compliance streamy a je **poison-pill safe**: chyby parsování/zpracování se zalogují a zpráva se ackne, takže jeden vadný event nemůže zaseknout consumer group (kyc-/aml-service zůstávají zdrojem pravdy a mohou přehrát).

- `openbank.kyc.events` → `KYC_CASE_APPROVED` → `kycStatus=APPROVED`; `KYC_CASE_REJECTED` → `REJECTED`. Ostatní typy eventů jsou ignorovány (žádné koncové rozhodnutí).
- `openbank.aml.events` → `newStatus/status = CLEARED` → `amlStatus=CLEARED`; `BLOCKED` → `amlStatus=BLOCKED`. Ostatní ignorovány.

`PartyService.deriveStatus(kyc, aml, current)` poté spočítá stav party (fail-closed):

```
CLOSED                                        → zůstává CLOSED (nikdy se znovu neotevře)
kyc == REJECTED  NEBO  aml == BLOCKED         → SUSPENDED
kyc == APPROVED  A  aml == CLEARED            → ACTIVE
jinak                                          → PENDING_KYC
```

## Komponenty z `openbank-libs`

| Modul | Využití zde |
|---|---|
| `libs.api.error.ApiError` | jednotný chybový model v `ExceptionMappers` |
| `libs.api.pagination.CursorPage / PageInfo / CursorEncoder` | keyset-cursor vyhledávání podle jména |
| `libs.api.search.SearchRequest` | DB-safety pojistky (omezení velikosti stránky, min. délka termu, escapování LIKE) pro vyhledávání ADR-0055 |
| `libs.flags.FeatureClient / @FeatureFlag` | vyhodnocení feature flagů (ADR-0067) |
| `libs.authz.@Authorize` | OPA autorizace (ADR-0034) |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` build metadata |

## Principy

1. **Hranice agregátu = Party** — dokumenty a stav životního cyklu jsou děti party.
2. **Aktivace má jedinou autoritu** — pouze party-service překlopí party na ACTIVE, s bránou na KYC i AML.
3. **Doménové eventy jako první** — každá změna stavu emituje doménový event; outbox garantuje doručení.
4. **Minimalizace PII** — rodné číslo zde není nikdy uloženo ani vyhledatelné; vyhledávání podle jména je jediný fuzzy lookup.
5. **Fail-static závislosti** — bez flagd / bez OPA služba stále obsluhuje provoz (defaulty volajícího / advisory režim).
