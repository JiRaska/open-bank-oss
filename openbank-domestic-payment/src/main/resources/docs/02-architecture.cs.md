# Architektura

## C4 — Systémový kontext

```mermaid
graph LR
  ch[kanály / operátoři]
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
  redis[(Valkey<br/>four-eyes schvalování)]

  ch -- "POST/GET/PATCH /domestic-payments" --> dp
  admin -- "čtení + ruční přechody" --> dp
  dp -- "screen plátce+příjemce (sync)" --> sanc
  dp -- "otevři AML případ (hit/review/výpadek)" --> aml

  dp --> db
  dp -- "outbox → publish" --> kafka
  dp -- "stav schvalovacího workflow" --> redis

  kafka --> clr
  kafka --> led
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-domestic-payment (Quarkus, reaktivní)"
    direction TB
    rest[REST<br/>DomesticPaymentResource<br/>ExceptionMappers]
    uc[Application<br/>DomesticPaymentService]
    dom[Domain<br/>DomesticPayment + stavový automat<br/>ScreeningPolicy + události]
    persist[Persistence<br/>DomesticPaymentRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox[Outbox<br/>DomesticPaymentOutboxDispatcher<br/>poll á 5s, dávka 25]
    kafkaPub[Kafka publisher<br/>KafkaDomesticPaymentEventPublisher]
    idem[Idempotence<br/>otisk normalizovaného požadavku]
    sancCli[Sanctions klient<br/>SanctionsScreeningAdapter]
    amlCli[AML klient<br/>AmlCaseAdapter]
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

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters** (ADR-0002):

```
com.openbank.domestic/
├── domain/                    ◄── jádro — bez závislostí na frameworku
│   ├── model/                 DomesticPayment, enumy stav / priorita / scope / důvod zamítnutí,
│   │                          transitionTo() + canTransitionTo() (stavový automat)
│   ├── event/                 DomesticPaymentCreatedEvent, DomesticPaymentStatusChangedEvent
│   └── screening/             ScreeningPolicy (čisté rozhodnutí), ScreeningResult, ScreeningDecision
│
├── application/               ◄── orchestrace use-case
│   ├── port/in/               DomesticPaymentUseCase + příkazy / dotazy
│   ├── port/out/              DomesticPaymentRepository, OutboxRepository, EventPublisher,
│   │                          SanctionsScreeningPort, AmlCasePort
│   └── usecase/               DomesticPaymentService (založení → persist → screening → přechod)
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  DomesticPaymentResource, DTO, ExceptionMappers
    ├── persistence/           implementace repository, JPA entity, mappery
    ├── outbox/                DomesticPaymentOutboxDispatcher (scheduled, fault-tolerant)
    ├── kafka/                 KafkaDomesticPaymentEventPublisher
    ├── approval/              ApprovalConfig (pouze four-eyes workflow v Redisu)
    ├── client/               SanctionsServiceClient + adaptér, AmlServiceClient + adaptér
    └── authz/                AuthzProducer (OPA, ADR-0034)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doména nikdy neimportuje balíček sanctions-service — `ScreeningMatchStatus` je lokální zrcadlový enum; adaptér mapuje vzdálený stav na něj.

## Screeningová brána (ADR-0032)

Screening je první krok zpracování a běží synchronně, **až poté**, co je řádek `RECEIVED` trvale uložen, takže platba se nikdy neztratí, pokud screening následně selže:

```mermaid
sequenceDiagram
  participant C as Klient
  participant R as DomesticPaymentResource
  participant S as DomesticPaymentService
  participant DB as PostgreSQL
  participant Sanc as sanctions-service
  participant Aml as aml-service

  C->>R: POST /domestic-payments (Idempotency-Key)
  R->>S: createPayment(cmd)
  S->>DB: INSERT platba (RECEIVED) + outbox (created) [1 TX]
  S->>Sanc: screen(jméno plátce) + screen(jméno příjemce)
  alt CLEAR
    S->>DB: přechod → VALIDATED + outbox [1 TX]
  else REVIEW (potenciální zásah ≤ práh)
    S->>Aml: otevři případ (HIGH, AML_HOLD)
    Note over S: zůstává RECEIVED k rozhodnutí člověka
  else BLOCK (HIT / ESCALATED / potenciální > 0.85)
    S->>Aml: otevři případ (CRITICAL, SANCTIONS_HIT)
    S->>DB: přechod → REJECTED (SANCTIONS_HIT) + outbox [1 TX]
  else screening nedostupný
    S->>Aml: otevři případ (MEDIUM, SCREENING_UNAVAILABLE)
    Note over S: zůstává RECEIVED (fail-closed)
  end
  R-->>C: 201 Created (finální stav v těle)
```

`ScreeningPolicy.decide()` je čistá funkce: **BLOCK** dominuje **REVIEW** dominuje **CLEAR**. BLOCK = jakýkoliv `HIT`/`ESCALATED` nebo `POTENTIAL_HIT` striktně nad `POTENTIAL_HIT_BLOCK_THRESHOLD` (0.85); REVIEW = jakýkoliv jiný `POTENTIAL_HIT`; CLEAR = `CLEAR`/`WHITELISTED` nebo prázdné. Otevření AML případu je best-effort — výpadek úložiště případů nesmí změnit screeningový verdikt.

## Outbox tok

```mermaid
sequenceDiagram
  participant S as DomesticPaymentService
  participant DB as PostgreSQL
  participant D as DomesticPaymentOutboxDispatcher
  participant K as Kafka

  S->>DB: INSERT platba + INSERT outbox(status=PENDING) [stejná TX]
  loop @Scheduled á 5s (dávka 25, SKIP soubězné)
    D->>DB: listProcessable(25)  (PENDING, nejstarší první)
    D->>K: publishWithResilience(payload)  (CircuitBreaker+Retry+Timeout+Bulkhead)
    alt publikováno
      D->>DB: markSent(eventId)
    else selhalo
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. Doručení at-least-once; konzumenti musí být idempotentní. Volání publish je obalené MicroProfile fault-tolerance (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`).

## Klíčové porty (application/port/out)

| Port | Odpovědnost |
|---|---|
| `DomesticPaymentRepository` | persist/find/list plateb; `save`/`update` přijímají outbox zprávu, takže řádek a událost commitnou atomicky |
| `DomesticPaymentOutboxRepository` | drénování outboxu — `listProcessable`, `markSent`, `markFailed` |
| `DomesticPaymentEventPublisher` | serializace payloadů `created`/`status-changed` při zápisu; `publish` je transportní volání při drénování |
| `SanctionsScreeningPort` | screening jména; vyhazuje `ScreeningUnavailableException`, když je sanctions-service nedostupná (→ fail-closed) |
| `AmlCasePort` | otevření AML případu (úroveň rizika + kód alertu + matched entity) |

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.persistence.outbox` | konvence outbox entity/repository |
| `libs.api.error.ApiError` / `ErrorCode` | jednotná chybová obálka (404 NOT_FOUND, 409 CONFLICT) |
| `libs.authz.@Authorize` | OPA autorizace při přechodu stavu (ADR-0034) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` build metadata |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |

## Principy

1. **Hranice agregátu = DomesticPayment** — stavový automat (`transitionTo`/`canTransitionTo`) je jediná legální cesta mutace.
2. **Persist před screeningem** — řádek `RECEIVED` + outbox jsou commitnuty před synchronním screeningovým voláním, takže platba se nikdy neztratí.
3. **Fail closed** — výpadek sanctions-service drží platbu v `RECEIVED`; nikdy se automaticky neuvolní.
4. **Žádné vzdálené volání uvnitř perzistenční transakce** — screening a volání AML případu probíhají mezi transakcemi.
5. **Trvalá idempotence svázaná s aktérem** — `Idempotency-Key` je povinný; Postgres uloží otisk normalizovaného požadavku atomicky s platbou/outboxem, přesný replay vrátí tento řádek a odlišný požadavek skončí 409.
