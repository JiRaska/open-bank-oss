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

## Vrstvy

```
com.openbank.balance/
├── domain/
│   └── model/Balance.kt              ← invarianty (arrangedOverdraft ≥ 0, version monotonic)
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

## Klíčové invariantní

1. **`(account_id, currency)` UNIQUE** — jeden zůstatek per účet × měnu
2. **Optimistic lock** — `version` se zvyšuje atomicky; concurrent debit od různých autorizací nebude double-spend
3. **`arrangedOverdraftLimit ≥ 0`** — vynuceno v `Balance.init {}` block
4. **Available formula nezná podmínky**: `available = booked − reserved − pendingDebit + arranged_overdraft`. Nikde else nesmí být alternativní výpočet.
5. **Outbox-first**: každá změna zůstatku → DB UPDATE + outbox INSERT v jedné TX

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

V `Captured` se reserved snižuje a pending zvyšuje. Skutečný transaction event později booked zaktualizuje.

## Komponenty z openbank-libs

| Modul | Použití |
|---|---|
| `libs.domain.money.Money` + `CurrencyCode` | typesafe zůstatky |
| `libs.domain.identifiers.AccountId` | typesafe ID |
| `libs.persistence.outbox` | abstract outbox entity + dispatcher loop |
| `libs.web.ServiceInfoResource` | `/api/v1/info` |
| `libs.docs.DocsResource` | **tahle dokumentace** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | tech-stack snapshot |
