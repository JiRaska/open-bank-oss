# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui<br/>compliance cockpit]
  party[party-service]
  pay[platební služby<br/>sepa/instant/domestic/swift]
  audit[audit-service]

  aml[(aml-service)]:::svc
  db[(PostgreSQL<br/>db: openbank_aml)]
  kafkaOut[(Kafka<br/>openbank.aml.events)]
  kafkaIn[(Kafka<br/>openbank.party.events)]
  redis[(Valkey<br/>idempotence)]

  admin -- "POST /aml/cases<br/>PUT .../decision" --> aml
  pay -- "POST /aml/cases<br/>(screening brána)" --> aml
  party -- "PARTY_CREATED" --> kafkaIn
  kafkaIn --> aml

  aml --> db
  aml -- "outbox → publish" --> kafkaOut
  aml --> redis

  kafkaOut --> party
  kafkaOut --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-aml-service (Quarkus 3.x, reaktivní)"
    direction TB
    rest[REST<br/>AmlCaseResource<br/>ExceptionMappers]
    consumer[Kafka in<br/>PartyEventConsumer]
    uc[Application<br/>AmlCaseService]
    dom[Domain<br/>AmlCase + stavový automat<br/>+ doménové události]
    persist[Persistence<br/>AmlCaseRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>AmlOutboxDispatcher<br/>@Scheduled každých 5s"]
    pub[Kafka out<br/>KafkaAmlOutboxEventPublisher]
    idem[Idempotence<br/>RedisIdempotencyStore]
  end

  rest --> uc
  consumer --> uc
  uc --> dom
  uc --> persist
  persist --> outbox
  outbox --> pub
  rest --> idem

  persist -.-> db[(PostgreSQL)]
  pub -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
```

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters**:

```
com.openbank.aml/
├── domain/                    ◄── jádro — bez frameworkových závislostí
│   ├── model/                 AmlCase, AmlCaseStatus, ScreeningType, AmlRiskLevel
│   └── event/                 AmlCaseCreatedEvent, AmlCaseStatusChangedEvent
│
├── application/               ◄── orchestrace use-case
│   ├── port/in/               AmlCaseUseCase + commands/queries
│   ├── port/out/              AmlCaseRepository, AmlOutboxPort
│   └── usecase/               AmlCaseService
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  AmlCaseResource, DTO, ExceptionMappers
    ├── kafka/                 PartyEventConsumer (in), KafkaAmlOutboxEventPublisher (out)
    ├── outbox/                AmlOutboxDispatcher (scheduled)
    ├── persistence/           AmlCaseEntity, AmlOutboxEntity, mappery, repository impl
    ├── idempotency/           IdempotencyConfig (@Produces RedisIdempotencyStore)
    └── authz/                 AuthzProducer (OPA PDP, ADR-0034)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód nikdy nevidí JPA, Kafku ani REST DTO. Stavový automat případu (`AmlCase.transitionTo` / `canTransitionTo`) žije celý v doménové vrstvě.

## Stavový automat případu

```mermaid
stateDiagram-v2
  [*] --> OPEN: riziko LOW / MEDIUM
  [*] --> UNDER_REVIEW: riziko HIGH / CRITICAL
  OPEN --> UNDER_REVIEW
  OPEN --> ESCALATED
  OPEN --> CLEARED
  OPEN --> BLOCKED
  UNDER_REVIEW --> ESCALATED
  UNDER_REVIEW --> CLEARED
  UNDER_REVIEW --> BLOCKED
  ESCALATED --> UNDER_REVIEW
  ESCALATED --> CLEARED
  ESCALATED --> BLOCKED
  CLEARED --> [*]
  BLOCKED --> [*]
```

Invarianty vynucené v doméně: terminální stav (`CLEARED`/`BLOCKED`) nelze měnit; `decidedBy` je povinné při každém přechodu; `decisionReason` je povinné při přechodu na `BLOCKED`.

## Outbox tok (ADR-0050)

```mermaid
sequenceDiagram
  participant C as Klient / Konzument
  participant R as AmlCaseResource
  participant S as AmlCaseService
  participant DB as PostgreSQL
  participant D as AmlOutboxDispatcher
  participant P as KafkaAmlOutboxEventPublisher
  participant K as Kafka

  C->>R: POST /aml/cases (Idempotency-Key)
  R->>S: createCase(...)
  S->>DB: BEGIN TX
  S->>DB: INSERT INTO aml_cases
  S->>DB: INSERT INTO aml_outbox<br/>(event=aml.case.created.v1, status=PENDING)
  S->>DB: COMMIT
  R-->>C: 201 Created

  loop @Scheduled každých 5s (SKIP overlap, replicas:1)
    D->>DB: SELECT processable FROM aml_outbox (dávka 25)
    D->>P: publishResilient(entry)
    P->>K: send do openbank.aml.events<br/>key=aggregateId, hlavičky ce-id/idempotency-key/ce-type
    D->>DB: UPDATE aml_outbox SET status=SENT (nebo markFailed)
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. Dispatcher je **jediný writer** — `concurrentExecution = SKIP` brání překryvu v rámci JVM a Deployment je pinován na `replicas: 1`; položky se zpracovávají sekvenčně pro zachování pořadí v rámci agregátu. Selhání publikace jednoho řádku jsou izolována (recover → `markFailed`), takže jeden vadný řádek nikdy nezruší celou dávku; opakovaná selhání jsou omezena přechodem do DEAD (ADR-0050 N5). Publisher obaluje odeslání MicroProfile Fault Tolerance (`@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`).

## Tok příchozích událostí

`PartyEventConsumer` (`@Incoming("party-events-in")`) čte `openbank.party.events`, filtruje `eventType == PARTY_CREATED` a `partyType == INDIVIDUAL` a zakládá `CUSTOMER_ONBOARDING` případ s idempotenčním klíčem `"<partyId>:CUSTOMER_ONBOARDING"` (bezpečné při redelivery). **Pouze v sandboxu** (`openbank.aml.auto-clear=true`, výchozí `false`) případ následně auto-clearuje, aby klient prošel AML klíčem aktivační brány bez analytika. Produkce zachovává decision endpoint ve čtyřech očích jako jedinou cestu do terminálního stavu. Konzument je odolný vůči poison-pill: selhání jsou logována a ACK.

## Klíčové porty

| Port | Směr | Adaptér |
|---|---|---|
| `AmlCaseUseCase` | in | `AmlCaseService`, volá `AmlCaseResource` a `PartyEventConsumer` |
| `AmlCaseRepository` | out | `AmlCaseRepositoryImpl` (Hibernate Reactive / Panache) |
| `AmlOutboxPort` | out | `AmlOutboxRepositoryImpl` + `AmlOutboxDispatcher` |
| `IdempotencyStore` | out | `RedisIdempotencyStore` (openbank-libs) |
| `PolicyDecisionPoint` | out | `OpaSidecarPolicyDecisionPoint` (openbank-libs, přes `AuthzProducer`) |

## Komponenty z `openbank-libs`

| Modul | Využití zde |
|---|---|
| `libs.idempotency.IdempotencyStore` + `RedisIdempotencyStore` | edge idempotence na `POST` |
| `libs.authz.Authorize` + `PolicyDecisionPoint` | `@Authorize` na decision endpointu, OPA PDP |
| `libs.api.error.ApiError` / `ErrorCode` | jednotný model chyb v `ExceptionMappers` |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |

## Principy

1. **Hranice agregátu = AmlCase** — přechody případu jsou atomické; agregát a jeho doménová událost se commitují společně přes outbox.
2. **Doménové události na prvním místě** — každá změna stavu emituje verzovanou doménovou událost (`...v1`).
3. **Žádná vzdálená volání v TX** — synchronně v rámci request/response, asynchronně přes outbox + Kafka.
4. **Idempotence na hraně** — `Idempotency-Key` na `POST`, plus unikátní `idempotency_key` sloupec na případu.
5. **Čtyři oči pro terminální rozhodnutí** — sandbox auto-clear je za feature flagem vypnutý; produkce vyžaduje explicitní rozhodnutí analytika.
