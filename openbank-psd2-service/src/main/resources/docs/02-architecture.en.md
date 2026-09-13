# Architecture

## C4 — System Context

```mermaid
graph LR
  tpp[TPP<br/>AISP / PISP]
  reg[tpp-registry-service]
  cons[consent-service]
  acc[account-service]
  tx[transaction-service]
  audit[audit-service / TPP webhooks]

  psd2[(psd2-service)]:::svc
  kafka[(Kafka<br/>openbank.psd2.events)]
  redis[(Valkey<br/>idempotency)]
  db[(PostgreSQL<br/>psd2_outbox only)]

  tpp -- "Open Banking v2<br/>QWAC / X-TPP-ID" --> psd2
  psd2 -- "role check (AISP/PISP)" --> reg
  psd2 -- "validate / create / revoke consent" --> cons
  psd2 -- "read accounts/balances/tx" --> acc
  psd2 -- "initiate payment / status" --> tx
  psd2 --> redis
  psd2 -- "outbox → publish" --> db
  db -. "drained by dispatcher" .-> kafka
  kafka --> audit

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-psd2-service (Quarkus 3.x, JDK 25)"
    direction TB
    rest[REST adapters<br/>AisResource / PisResource<br/>ConsentResource / SandboxResource]
    filt[EidasMtlsFilter<br/>AUTHENTICATION priority]
    uc[Application<br/>AccountInformationService<br/>ConsentManagementService<br/>PaymentInitiationService]
    dom[Domain<br/>Open Banking models<br/>PaymentProduct / ConsentStatusOb / …]
    cli[Outbound clients<br/>Resilient* → Stub*<br/>account / consent / transaction]
    guard[TppAuthorizationGuard<br/>RestClient → tpp-registry]
    outbox["Outbox<br/>Psd2OutboxDispatcher<br/>@Scheduled every 5s"]
    msg[Messaging<br/>KafkaPsd2OutboxEventPublisher]
    idem[Idempotency<br/>RedisIdempotencyStore]
  end

  filt --> guard
  rest --> uc
  uc --> dom
  uc --> cli
  rest --> idem
  outbox --> msg
  cli -.-> ext[(downstream services)]
  guard -.-> reg[(tpp-registry)]
  msg -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
  outbox -.-> db[(psd2_outbox)]
```

## Hexagonal layers

The package layout reflects **ports-and-adapters** (ADR [0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md)):

```
com.openbank.psd2/
├── domain/                       ◄── core — no framework dependencies
│   └── model/                    ObModels.kt: ObAccount, ObBalance, ObTransaction,
│                                 PaymentInitiation, DomesticCzPayment, SipoPayment,
│                                 ObConsentRequest/Response, PaymentProduct,
│                                 PaymentStatus, ConsentStatusOb, TppWebhookEvent
│
├── application/                  ◄── use-case orchestration
│   ├── port/in/                  inbound ports + commands/queries (Psd2UseCases.kt)
│   ├── port/out/                 outbound ports: AccountServiceClient,
│   │                             ConsentServiceClient, TransactionServiceClient,
│   │                             TppWebhookPublisher, Psd2Outbox* (repo/publisher)
│   └── usecase/                  AccountInformationService, ConsentManagementService,
│                                 PaymentInitiationService (Psd2Services.kt)
│
└── infrastructure/               ◄── adapters
    ├── rest/                     AisResource, PisResource, ConsentResource,
    │   │                         SandboxResource, ExceptionMappers
    │   └── filter/               EidasMtlsFilter (TPP auth)
    ├── client/                   TppRegistryClient, ResilientClients, StubClients
    ├── outbox/                   Psd2OutboxDispatcher (@Scheduled)
    ├── messaging/                KafkaPsd2OutboxEventPublisher
    ├── persistence/              Psd2OutboxEntity, Psd2OutboxRepositoryImpl
    └── idempotency/              IdempotencyConfig (@Produces RedisIdempotencyStore)
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. The domain layer holds only Open Banking value models and carries no JAX-RS, Kafka or REST-client types.

## TPP authentication flow

```mermaid
sequenceDiagram
  participant T as TPP
  participant F as EidasMtlsFilter
  participant G as TppAuthorizationGuard
  participant R as tpp-registry-service
  participant Res as AIS/PIS/Consent resource

  T->>F: request (QWAC cert SSL-CLIENT-S-DN or X-TPP-ID)
  alt no TPP identity
    F-->>T: 401 CERTIFICATE_MISSING
  else
    F->>G: requireAuthorized(tppId, AISP|PISP)
    Note over F,G: role = PISP for /payments, else AISP
    G->>R: GET /api/v1/tpp-registry/check?tppId&role
    alt circuit open / registry error
      F-->>T: 503 SERVICE_UNAVAILABLE
    else not authorized
      F-->>T: 401 CERTIFICATE_INVALID
    else authorized
      F->>Res: set ctx "tppId", continue
      Res-->>T: 2xx
    end
  end
```

Sandbox paths (`open-banking/sandbox/...`) are exempt from the filter and return deterministic fixtures.

## Outbox flow

```mermaid
sequenceDiagram
  participant App as Application
  participant DB as psd2_outbox
  participant D as Psd2OutboxDispatcher
  participant K as Kafka (openbank.psd2.events)

  App->>DB: INSERT (event_id, aggregate_id, event_type, payload, status=PENDING)
  loop @Scheduled every 5s (delayed 5s, SKIP concurrent)
    D->>DB: listProcessable(limit=25)
    D->>K: publishWithResilience(payload) (bulkhead 1, CB, retry, timeout 3s)
    alt success
      D->>DB: markSent(eventId, sent_at)
    else failure
      D->>DB: markFailed(eventId, error)
    end
  end
```

**Why outbox:** transactional consistency between a state change and the Kafka publish; at-least-once delivery with idempotent consumers. The dispatcher swallows scheduler-level exceptions so a transient Kafka outage never crashes the scheduler.

## Resilience model

Every outbound dependency call is wrapped in MicroProfile Fault Tolerance:

| Caller | Pattern | Notable behavior |
|---|---|---|
| `TppAuthorizationGuard` → tpp-registry | timeout 2 s, retry 2, circuit breaker | circuit-open ⇒ `503 SERVICE_UNAVAILABLE` to the TPP |
| `ResilientAccountServiceClient` → account-service | CB + retry + timeout 3–5 s, fallback | fallback returns empty list / null (degrade reads gracefully) |
| `ResilientConsentServiceClient` → consent-service | CB + retry + timeout 2–3 s, fallback | **`validateConsent` fallback returns `false` — fail closed** |
| `ResilientTransactionServiceClient` → transaction-service | CB (failureRatio 0.3) + retry + timeout 10 s | `initiatePayment` has **no fallback** — a failure propagates (never silently "succeed") |
| `Psd2OutboxDispatcher.publishWithResilience` | bulkhead 1, CB, retry, timeout 3 s | protects the Kafka publish path |

These knobs are mirrored in `application.yaml` under `openbank.resilience.*`.

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.idempotency.IdempotencyStore` / `impl.RedisIdempotencyStore` | replay-safe PIS / consent creation (per-service `@Produces`) |
| outbox plumbing | `Psd2OutboxEntity` / repository / dispatcher conventions |
| docs resource | serves **this documentation** at `/q/openbank/docs` |
| service-info / build-info | `/api/v1/info` build metadata, OpenTelemetry resource attrs |

## Principles

1. **Stateless facade** — no domain data persisted; the only table is the outbox.
2. **Consent-gated** — every AIS read and PIS initiation validates consent first; on doubt, deny.
3. **Translate, don't duplicate** — Open Banking models map to internal calls; no second copy of accounts/consents/payments.
4. **Idempotence at the edge** — PIS requires `Idempotency-Key`; consent creation keys on `X-TPP-ID` + `X-Request-ID`.
5. **Fail closed on auth and consent; never fake a payment success.**
