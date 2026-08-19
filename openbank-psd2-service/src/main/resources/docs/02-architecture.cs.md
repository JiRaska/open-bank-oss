# Architektura

## C4 — Systémový kontext

```mermaid
graph LR
  tpp[TPP<br/>AISP / PISP]
  reg[tpp-registry-service]
  cons[consent-service]
  acc[account-service]
  tx[transaction-service]
  audit[audit-service / TPP webhooky]

  psd2[(psd2-service)]:::svc
  kafka[(Kafka<br/>openbank.psd2.events)]
  redis[(Valkey<br/>idempotence)]
  db[(PostgreSQL<br/>pouze psd2_outbox)]

  tpp -- "Open Banking v2<br/>QWAC / X-TPP-ID" --> psd2
  psd2 -- "kontrola role (AISP/PISP)" --> reg
  psd2 -- "ověř / vytvoř / zruš souhlas" --> cons
  psd2 -- "čti účty/zůstatky/tx" --> acc
  psd2 -- "iniciuj platbu / stav" --> tx
  psd2 --> redis
  psd2 -- "outbox → publish" --> db
  db -. "drainuje dispatcher" .-> kafka
  kafka --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-psd2-service (Quarkus 3.x, JDK 25)"
    direction TB
    rest[REST adaptéry<br/>AisResource / PisResource<br/>ConsentResource / SandboxResource]
    filt[EidasMtlsFilter<br/>priorita AUTHENTICATION]
    uc[Application<br/>AccountInformationService<br/>ConsentManagementService<br/>PaymentInitiationService]
    dom[Doména<br/>Open Banking modely<br/>PaymentProduct / ConsentStatusOb / …]
    cli[Výstupní klienti<br/>Resilient* → Stub*<br/>account / consent / transaction]
    guard[TppAuthorizationGuard<br/>RestClient → tpp-registry]
    outbox["Outbox<br/>Psd2OutboxDispatcher<br/>@Scheduled každých 5s"]
    msg[Messaging<br/>KafkaPsd2OutboxEventPublisher]
    idem[Idempotence<br/>RedisIdempotencyStore]
  end

  filt --> guard
  rest --> uc
  uc --> dom
  uc --> cli
  rest --> idem
  outbox --> msg
  cli -.-> ext[(downstream služby)]
  guard -.-> reg[(tpp-registry)]
  msg -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
  outbox -.-> db[(psd2_outbox)]
```

## Hexagonální vrstvy

Rozložení balíčků odráží **ports-and-adapters** (ADR [0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md)):

```
com.openbank.psd2/
├── domain/                       ◄── jádro — bez frameworkových závislostí
│   └── model/                    ObModels.kt: ObAccount, ObBalance, ObTransaction,
│                                 PaymentInitiation, DomesticCzPayment, SipoPayment,
│                                 ObConsentRequest/Response, PaymentProduct,
│                                 PaymentStatus, ConsentStatusOb, TppWebhookEvent
│
├── application/                  ◄── orchestrace případů užití
│   ├── port/in/                  vstupní porty + commands/queries (Psd2UseCases.kt)
│   ├── port/out/                 výstupní porty: AccountServiceClient,
│   │                             ConsentServiceClient, TransactionServiceClient,
│   │                             TppWebhookPublisher, Psd2Outbox* (repo/publisher)
│   └── usecase/                  AccountInformationService, ConsentManagementService,
│                                 PaymentInitiationService (Psd2Services.kt)
│
└── infrastructure/               ◄── adaptéry
    ├── rest/                     AisResource, PisResource, ConsentResource,
    │   │                         SandboxResource, ExceptionMappers
    │   └── filter/               EidasMtlsFilter (autentizace TPP)
    ├── client/                   TppRegistryClient, ResilientClients, StubClients
    ├── outbox/                   Psd2OutboxDispatcher (@Scheduled)
    ├── messaging/                KafkaPsd2OutboxEventPublisher
    ├── persistence/              Psd2OutboxEntity, Psd2OutboxRepositoryImpl
    └── idempotency/              IdempotencyConfig (@Produces RedisIdempotencyStore)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménová vrstva drží jen Open Banking hodnotové modely a nenese žádné JAX-RS, Kafka ani REST-client typy.

## Tok autentizace TPP

```mermaid
sequenceDiagram
  participant T as TPP
  participant F as EidasMtlsFilter
  participant G as TppAuthorizationGuard
  participant R as tpp-registry-service
  participant Res as AIS/PIS/Consent zdroj

  T->>F: požadavek (QWAC cert SSL-CLIENT-S-DN nebo X-TPP-ID)
  alt bez identity TPP
    F-->>T: 401 CERTIFICATE_MISSING
  else
    F->>G: requireAuthorized(tppId, AISP|PISP)
    Note over F,G: role = PISP pro /payments, jinak AISP
    G->>R: GET /api/v1/tpp-registry/check?tppId&role
    alt circuit open / chyba registru
      F-->>T: 503 SERVICE_UNAVAILABLE
    else neautorizováno
      F-->>T: 401 CERTIFICATE_INVALID
    else autorizováno
      F->>Res: nastav ctx "tppId", pokračuj
      Res-->>T: 2xx
    end
  end
```

Sandbox cesty (`open-banking/sandbox/...`) jsou z filtru vyňaty a vracejí deterministické fixtures.

## Outbox tok

```mermaid
sequenceDiagram
  participant App as Application
  participant DB as psd2_outbox
  participant D as Psd2OutboxDispatcher
  participant K as Kafka (openbank.psd2.events)

  App->>DB: INSERT (event_id, aggregate_id, event_type, payload, status=PENDING)
  loop @Scheduled každých 5s (delayed 5s, SKIP concurrent)
    D->>DB: listProcessable(limit=25)
    D->>K: publishWithResilience(payload) (bulkhead 1, CB, retry, timeout 3s)
    alt úspěch
      D->>DB: markSent(eventId, sent_at)
    else selhání
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Proč outbox:** transakční konzistence mezi změnou stavu a publikací do Kafky; doručení at-least-once s idempotentními konzumenty. Dispatcher polyká výjimky na úrovni plánovače, takže přechodný výpadek Kafky nikdy neshodí scheduler.

## Model odolnosti

Každé volání výstupní závislosti je obaleno MicroProfile Fault Tolerance:

| Volající | Vzor | Pozoruhodné chování |
|---|---|---|
| `TppAuthorizationGuard` → tpp-registry | timeout 2 s, retry 2, circuit breaker | circuit-open ⇒ `503 SERVICE_UNAVAILABLE` pro TPP |
| `ResilientAccountServiceClient` → account-service | CB + retry + timeout 3–5 s, fallback | fallback vrací prázdný seznam / null (čtení degraduje měkce) |
| `ResilientConsentServiceClient` → consent-service | CB + retry + timeout 2–3 s, fallback | **`validateConsent` fallback vrací `false` — selhává uzavřeně** |
| `ResilientTransactionServiceClient` → transaction-service | CB (failureRatio 0,3) + retry + timeout 10 s | `initiatePayment` **nemá fallback** — selhání se propaguje (nikdy tiše „úspěch") |
| `Psd2OutboxDispatcher.publishWithResilience` | bulkhead 1, CB, retry, timeout 3 s | chrání cestu publikace do Kafky |

Tyto parametry jsou zrcadleny v `application.yaml` pod `openbank.resilience.*`.

## Komponenty z `openbank-libs`

| Modul | Využití zde |
|---|---|
| `libs.idempotency.IdempotencyStore` / `impl.RedisIdempotencyStore` | replay-safe PIS / vytvoření souhlasu (per-service `@Produces`) |
| outbox plumbing | konvence `Psd2OutboxEntity` / repozitář / dispatcher |
| docs resource | obsluhuje **tuto dokumentaci** na `/q/openbank/docs` |
| service-info / build-info | build metadata na `/api/v1/info`, atributy OpenTelemetry resource |

## Principy

1. **Bezstavová fasáda** — žádná doménová data nejsou perzistována; jedinou tabulkou je outbox.
2. **Podmíněno souhlasem** — každé AIS čtení a PIS iniciace nejdřív ověří souhlas; při pochybnosti odepři.
3. **Překládej, neduplikuj** — Open Banking modely se mapují na interní volání; žádná druhá kopie účtů/souhlasů/plateb.
4. **Idempotence na okraji** — PIS vyžaduje `Idempotency-Key`; vytvoření souhlasu klíčuje na `X-TPP-ID` + `X-Request-ID`.
5. **Selhávej uzavřeně u autentizace a souhlasu; nikdy nepředstírej úspěch platby.**
