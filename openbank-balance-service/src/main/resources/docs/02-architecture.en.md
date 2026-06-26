# Architecture

## C4 — System Context

```mermaid
graph LR
  acc[account-service]
  txn[transaction-service]
  cards[card-issuance-service]
  pay[payment-services]
  notif[notification-service]
  fraud[fraud-detection]

  bal[(balance-service)]:::svc
  db[(PostgreSQL<br/>schema: balance)]
  kafka[(Kafka<br/>balance.events)]

  acc -- "account.opened" --> bal
  txn -- "transaction.committed" --> bal
  cards -- "POST /holds" --> bal
  pay -- "POST /holds, /capture" --> bal

  bal --> db
  bal -- "outbox → publish" --> kafka

  kafka --> acc
  kafka --> notif
  kafka --> fraud

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container

```mermaid
graph TB
  subgraph "openbank-balance-service"
    rest[REST<br/>BalanceResource]
    uc[Application<br/>BalanceService]
    dom[Domain<br/>Balance + invariants]
    persist[Persistence<br/>BalanceRepositoryImpl<br/>Panache]
    cons[Kafka consumer<br/>TransactionEventsConsumer]
    outbox[Outbox<br/>BalanceOutboxDispatcher]
  end

  rest --> uc
  cons --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  persist -.-> db[(PostgreSQL)]
  outbox -.-> kafka[(Kafka)]
  cons -.- kafka
```

## Layers

```
com.openbank.balance/
├── domain/
│   └── model/Balance.kt              ← invariants (arrangedOverdraft ≥ 0, version monotonic)
├── application/
│   ├── port/in/BalancePort.kt
│   ├── port/out/BalancePorts.kt + BalanceOutboxPort.kt
│   └── usecase/BalanceService.kt
└── infrastructure/
    ├── rest/                          BalanceResource, ExceptionMappers
    ├── persistence/repository/        BalanceRepositoryImpl (Panache)
    ├── persistence/entity/            BalanceEntity, BalanceOutboxEntity
    ├── outbox/                        BalanceOutboxDispatcher (@Scheduled)
    └── kafka/                         Producer + outbox publisher
```

## Core invariants

1. **`(account_id, currency)` UNIQUE** — one balance per account × currency
2. **Optimistic lock** — `version` increments atomically; concurrent debits from different authorisations cannot double-spend
3. **`arrangedOverdraftLimit ≥ 0`** — enforced in the `Balance.init {}` block
4. **The available formula has no conditions**: `available = booked − reserved − pendingDebit + arranged_overdraft`. No alternate calculation is allowed anywhere else.
5. **Outbox-first**: every balance change → DB UPDATE + outbox INSERT in a single TX

## Hold lifecycle

```mermaid
stateDiagram-v2
  [*] --> Created : POST /holds
  Created --> Captured : POST /holds/{id}/capture (settlement)
  Created --> Released : DELETE /holds/{id} (auth cancel)
  Created --> Expired : auto (TTL > expires_at)
  Captured --> [*]
  Released --> [*]
  Expired --> [*]
```

On `Captured` reserved decreases and pending increases. The real transaction event later updates booked.

## Components from openbank-libs

| Module | Use |
|---|---|
| `libs.domain.money.Money` + `CurrencyCode` | typesafe balances |
| `libs.domain.identifiers.AccountId` | typesafe ID |
| `libs.persistence.outbox` | abstract outbox entity + dispatcher loop |
| `libs.web.ServiceInfoResource` | `/api/v1/info` |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | tech-stack snapshot |
