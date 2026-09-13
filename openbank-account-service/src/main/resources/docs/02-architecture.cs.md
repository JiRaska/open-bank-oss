# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui]
  party[party-service]
  kyc[kyc-service]
  bal[balance-service]
  led[ledger-service]
  pay[payment-services]
  audit[audit-service]
  notif[notification]

  acc[(account-service)]:::svc
  db[(PostgreSQL<br/>schema: account)]
  kafka[(Kafka<br/>account.events.v1)]
  redis[(Valkey<br/>idempotency)]

  admin -- "POST/GET /accounts" --> acc
  party -. "ownerPartyId lookup" .-> acc
  pay -- "GET /accounts/{id}<br/>(validate IBAN+status)" --> acc
  kyc -. "AccountOpened consumer" .-> kafka

  acc --> db
  acc -- "outbox → publish" --> kafka
  acc --> redis

  kafka --> bal
  kafka --> led
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-account-service (Quarkus 3.33.2)"
    direction TB
    rest[REST<br/>AccountResource<br/>AuthorizationResource]
    uc[Application<br/>AccountService<br/>AuthorizationService]
    dom[Domain<br/>Account / Authorization / Balance<br/>+ domain events]
    persist[Persistence<br/>AccountRepositoryImpl<br/>JPA / Panache]
    outbox[Outbox<br/>AccountOutboxDispatcher<br/>polls every 500ms]
    idem[Idempotency<br/>RedisIdempotencyStore]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  rest --> idem

  persist -.-> db[(PostgreSQL)]
  outbox -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
```

## Hexagonální vrstvy

Adresářová struktura odráží **ports-and-adapters**:

```
com.openbank.account/
├── domain/                    ◄── core — žádné framework závislosti
│   ├── model/                 Account, AccountAuthorization, AccountBalance
│   ├── event/                 AccountOpened, AccountFrozen, …
│   ├── repository/            AccountRepository (port)
│   └── service/               domain logic (state transitions, invariants)
│
├── application/               ◄── use-case orchestrace
│   ├── port/                  inbound / outbound ports
│   └── usecase/               AccountService, AuthorizationService
│
└── infrastructure/            ◄── adapters
    ├── rest/                  JAX-RS resources, DTO mapping
    ├── persistence/           AccountRepositoryImpl (JPA/Panache)
    ├── outbox/                AccountOutboxDispatcher (scheduled)
    ├── messaging/             Kafka producer
    ├── idempotency/           Redis adapter
    └── config/                @Produces beans
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód nikdy nevidí JPA, Kafka, REST DTO.

## Outbox flow

```mermaid
sequenceDiagram
  participant C as Client
  participant R as AccountResource
  participant S as AccountService
  participant DB as PostgreSQL
  participant D as AccountOutboxDispatcher
  participant K as Kafka

  C->>R: POST /accounts (Idempotency-Key)
  R->>S: openAccount(...)
  S->>DB: BEGIN TX
  S->>DB: INSERT INTO account
  S->>DB: INSERT INTO account_outbox<br/>(event=AccountOpened, status=PENDING)
  S->>DB: COMMIT
  R-->>C: 201 Created

  loop every 500ms
    D->>DB: SELECT FROM account_outbox WHERE status=PENDING LIMIT 100
    D->>K: publish to openbank.account.events.v1
    D->>DB: UPDATE account_outbox SET status=PUBLISHED
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. At-least-once doručení, idempotentní spotřebitelé.

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.domain.account.Iban` | validace + normalizace IBAN |
| `libs.domain.money.Money` + `CurrencyCode` | hodnoty pro počáteční zůstatek, limity |
| `libs.domain.identifiers.AccountId` | typesafe ID místo UUID |
| `libs.idempotency.IdempotencyStore` | Redis-backed implementace |
| `libs.persistence.outbox` | OutboxEntity, OutboxRepository, OutboxDispatcherBase |
| `libs.security.BootstrapVerifier` — ⬜ **nekonzumuje se, neexistuje** | **Nic.** Tato třída v `openbank-libs` není a nikdy nebyla (`git grep BootstrapVerifier -- '*.kt'` vrací 0); ADR-0017 ji předepisuje a její delivery note uvádí, že dodána nebyla. Dev hesla drží mimo prod injektáž přes ESO/OpenBao `secretKeyRef` (ADR-0007), ne kód z libs (#8426) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **tahle dokumentace** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | runtime tech-stack snapshot |

## Principy

1. **Aggregate boundary = Account** — operace nad účtem jsou atomické, autorizace jsou child entity.
2. **Domain events first** — každá state-change emituje doménový event; infrastruktura ho serializuje.
3. **No remote calls in TX** — vše synchronní v rámci request-response, async přes outbox + Kafka.
4. **Idempotence at edge** — povinný `Idempotency-Key` na všech POST/PUT, deduplikace v Redis.
5. **PII minimalizace** — IBAN je PII (GDPR), v lozích maskován (`libs.security.PiiMask.maskIban`).
