# Architektura

## C4 — Systémový kontext

```mermaid
graph LR
  admin[admin-ui / kanály]
  sanc[sanctions-service]
  aml[aml-service]
  clr[clearing-service]
  led[ledger-service]
  audit[audit-service]
  notif[notification]
  opa[OPA sidecar]

  sepa[(sepa-payment-service)]:::svc
  db[(PostgreSQL<br/>openbank_sepa_payments)]
  kafka[(Kafka<br/>openbank.sepa.payment.events)]
  redis[(Valkey<br/>idempotence)]

  admin -- "POST/GET/PATCH /sepa-payments" --> sepa
  sepa -- "screen (sync, fail-closed)" --> sanc
  sepa -- "open case (best-effort)" --> aml
  sepa -. "authz kontrola" .-> opa

  sepa --> db
  sepa -- "outbox → publish" --> kafka
  sepa --> redis

  kafka --> clr
  kafka --> led
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-sepa-payment (Quarkus 3.x)"
    direction TB
    rest[REST<br/>SepaPaymentResource<br/>ExceptionMappers]
    uc[Application<br/>SepaPaymentService]
    dom[Domain<br/>SepaPayment + stavový automat<br/>ScreeningPolicy<br/>+ doménové události]
    persist[Persistence<br/>SepaPaymentRepositoryImpl<br/>SepaPaymentOutboxRepositoryImpl]
    outbox["Outbox<br/>SepaPaymentOutboxDispatcher<br/>@Scheduled každých 5s"]
    kafkaad[Kafka<br/>KafkaSepaPaymentEventPublisher]
    clients[Klienti<br/>SanctionsScreeningAdapter<br/>AmlCaseAdapter]
    idem[Idempotence<br/>libs IdempotencyStore]
  end

  rest --> uc
  rest --> idem
  uc --> dom
  uc --> persist
  uc --> clients
  outbox --> kafkaad

  persist -.-> db[(PostgreSQL)]
  kafkaad -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
  clients -.-> ext[sanctions / aml]
```

## Hexagonální vrstvy

Rozložení balíčků odráží **ports-and-adapters**:

```
com.openbank.sepa/
├── domain/                    ◄── jádro — žádné framework závislosti
│   ├── model/                 SepaPayment, SepaPaymentStatus, SepaPaymentType, SepaRejectReason
│   ├── event/                 SepaPaymentCreatedEvent, SepaPaymentStatusChangedEvent
│   └── screening/             ScreeningPolicy, ScreeningResult, ScreeningDecision (čisté)
│
├── application/               ◄── orchestrace use casů
│   ├── port/in/               SepaPaymentUseCase + commands/queries
│   ├── port/out/              SepaPaymentRepository, SepaPaymentOutboxRepository,
│   │                          SepaPaymentEventPublisher, SanctionsScreeningPort, AmlCasePort
│   └── usecase/               SepaPaymentService
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  SepaPaymentResource, DTO, ExceptionMappers
    ├── persistence/           entita + repository impl + mapper
    ├── outbox/                SepaPaymentOutboxDispatcher (scheduled)
    ├── kafka/                 KafkaSepaPaymentEventPublisher
    ├── client/                SanctionsScreeningAdapter, AmlCaseAdapter (+ REST klienti)
    ├── idempotency/           IdempotencyConfig
    └── authz/                 AuthzProducer (OPA, ADR-0034)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doména — včetně rozhodnutí `ScreeningPolicy` a stavového automatu `SepaPayment` — nemá žádné framework importy.

## Screening gate (ADR-0032)

```mermaid
sequenceDiagram
  participant C as Klient
  participant R as SepaPaymentResource
  participant S as SepaPaymentService
  participant DB as PostgreSQL
  participant Sanc as sanctions-service
  participant Aml as aml-service

  C->>R: POST /sepa-payments (Idempotency-Key)
  R->>S: createPayment(...)
  S->>DB: INSERT platba (RECEIVED) + outbox (created) — jedna TX
  S->>Sanc: screen(debtorName) + screen(creditorName)
  alt screening nedostupný
    Sanc--xS: chyba
    S->>Aml: openCase(MEDIUM, SCREENING_UNAVAILABLE) [best-effort]
    Note over S: platba DRŽENA v RECEIVED (fail-closed)
  else CLEAR
    S->>DB: přechod → VALIDATED + outbox (status-changed)
  else REVIEW (podprahový potenciální zásah)
    S->>Aml: openCase(HIGH, AML_HOLD) [best-effort]
    Note over S: platba DRŽENA v RECEIVED k lidskému rozhodnutí
  else BLOCK (zásah / eskalace / score > 0.85)
    S->>Aml: openCase(CRITICAL, SANCTIONS_HIT) [best-effort]
    S->>DB: přechod → REJECTED (SANCTIONS_HIT) + outbox
  end
  R-->>C: 201 Created (finální stav odráží verdikt)
```

`ScreeningPolicy.decide` je čistá a zrcadlí vlastní práh sanctions služby `isHighRisk` (`POTENTIAL_HIT_BLOCK_THRESHOLD = 0.85`): **BLOCK** dominuje **REVIEW** dominuje **CLEAR**. Otevření AML případu je best-effort — výpadek case-store nesmí nikdy překlopit již vynesený verdikt screeningu.

## Outbox tok

```mermaid
sequenceDiagram
  participant S as SepaPaymentService
  participant DB as PostgreSQL
  participant D as SepaPaymentOutboxDispatcher
  participant K as Kafka

  S->>DB: BEGIN TX
  S->>DB: INSERT/UPDATE sepa_payments
  S->>DB: INSERT sepa_payment_outbox (status=PENDING)
  S->>DB: COMMIT

  loop každých 5s (concurrentExecution = SKIP)
    D->>DB: SELECT processable FROM sepa_payment_outbox LIMIT 25
    D->>K: publishWithResilience(payload) [CircuitBreaker + Retry + Timeout + Bulkhead]
    alt publikováno
      D->>DB: markSent(eventId)
    else selhalo
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. At-least-once doručení; navazující konzumenti musí být idempotentní. Dispatcher obaluje publikaci do MicroProfile Fault Tolerance (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`), takže výpadek brokeru degraduje na retry, místo aby shodil scheduler.

## Klíčové porty

| Port (application/port) | Směr | Adaptér | Účel |
|---|---|---|---|
| `SepaPaymentUseCase` | in | `SepaPaymentResource` | create / get / list / transition |
| `SepaPaymentRepository` | out | `SepaPaymentRepositoryImpl` | perzistuje agregát + outbox v jedné TX |
| `SepaPaymentOutboxRepository` | out | `SepaPaymentOutboxRepositoryImpl` | vyprazdňuje outbox (listProcessable / markSent / markFailed) |
| `SepaPaymentEventPublisher` | out | `KafkaSepaPaymentEventPublisher` | serializuje payloady + publikuje do Kafky |
| `SanctionsScreeningPort` | out | `SanctionsScreeningAdapter` | synchronní screen, vyhazuje `ScreeningUnavailableException` (fail-closed) |
| `AmlCasePort` | out | `AmlCaseAdapter` | best-effort `openCase` při zásahu/zadržení |

## Principy

1. **Hranice agregátu = SepaPayment** — každá změna stavu je atomická a emituje doménovou událost.
2. **Screen před uvolněním** — řádek RECEIVED je trvale uložen *před* screeningem, takže platba se neztratí, pokud screening pak selže; fail-closed ji drží.
3. **Transakční outbox** — řádek DB a outbox zpráva commitují společně; publikace do Kafky je asynchronní přes dispatcher.
4. **Idempotence na hraně** — `Idempotency-Key` povinný při create; deduplikace v Redisu a přes UNIQUE `idempotency_key` v DB.
5. **Žádné side-effecty překlápějící verdikt** — otevření AML případu je best-effort a nikdy nemění rozhodnutí screeningu.
