# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  pay[platební služby<br/>sepa / domestic / swift / instant / SO / clearing]
  fx[fx-service]
  agent[agent-service]
  admin[admin-ui]

  bal[(balance-service)]
  led[(ledger-service)]
  audit[audit-service]
  notif[notification-service]

  tx[(transaction-service)]:::svc
  db[(PostgreSQL<br/>openbank_transactions)]
  kafka[(Kafka<br/>transaction.initiated)]

  pay -- "POST /transactions" --> tx
  agent -. "GET search/list (MCP)" .-> tx
  admin -- "GET search/list" --> tx
  tx -- "GET kurz" --> fx
  tx -- "hold / debet / kredit" --> bal
  tx -- "post / reverze journalu" --> led

  tx --> db
  tx -- "outbox → publish" --> kafka
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-transaction-service (Quarkus 3.x, reaktivní)"
    direction TB
    rest[REST<br/>TransactionResource<br/>ExceptionMappers]
    uc[Application<br/>TransactionService<br/>PaymentSagaOrchestrator<br/>PaymentJournalFactory]
    dom[Domain<br/>Transaction / PaymentSaga<br/>SettlementDateResolver<br/>+ doménové události]
    persist[Persistence<br/>PanacheTransactionRepository<br/>PanachePaymentSagaRepository<br/>Reactive Panache]
    outbox["Outbox<br/>TransactionOutboxDispatcher<br/>@Scheduled každých 5s"]
    clients[REST klienti<br/>LedgerCallGuard / LedgerRestClient<br/>BalanceCoverClient / FxRateClient]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  uc --> clients

  persist -.-> db[(PostgreSQL)]
  outbox -.-> kafka[(Kafka)]
  clients -.-> led[(ledger-service)]
  clients -.-> bal[(balance-service)]
  clients -.-> fx[(fx-service)]
```

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters** (ADR-0002):

```
com.openbank.transaction/
├── domain/                    ◄── jádro — žádné závislosti na frameworku
│   ├── model/                 Transaction, TransactionType, TransactionStatus
│   ├── saga/                  PaymentSaga, SagaState (používá libs SagaStateMachine, ADR-0045)
│   ├── settlement/            SettlementDateResolver (pravidla datumu valuty/zaúčtování)
│   └── event/                 TransactionInitiated / Completed / Failed
│
├── application/               ◄── orchestrace use-case
│   ├── port/in/               TransactionUseCase, příkazy a dotazy
│   ├── port/out/              TransactionRepository, PaymentSagaRepository,
│   │                          TransactionOutboxPort, TransactionEventPublisher,
│   │                          BalanceCoverPort, FxRatePort
│   └── usecase/               TransactionService, PaymentSagaOrchestrator, PaymentJournalFactory
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  TransactionResource, ExceptionMappers (mapování DTO)
    ├── persistence/           Panache repozitáře + entity
    ├── outbox/                TransactionOutboxDispatcher (@Scheduled)
    ├── messaging/             LoggingTransactionEventPublisher (Kafka)
    └── client/                LedgerRestClient, LedgerCallGuard, BalanceCoverClient, FxRateClient
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doménový kód nikdy nevidí Panache, Kafku ani REST DTO.

## Platební sága

Orchestrátor (`PaymentSagaOrchestrator`) spouští pohyb peněz **synchronně** v rámci iniciačního požadavku a prochází řádek `PaymentSaga` stavovým automatem validovaným sdíleným primitivem `SagaStateMachine` (ADR-0045).

```mermaid
sequenceDiagram
  participant TS as TransactionService
  participant Saga as PaymentSagaOrchestrator
  participant Bal as balance-service
  participant Led as ledger-service

  TS->>Saga: startSaga(transaction)
  Saga->>Saga: STARTED → PAYMENT_INITIATED
  opt zdrojový účet přítomen
    Saga->>Saga: → FUNDS_RESERVED
    Saga->>Bal: placeHold(zdroj, baseAmount, TTL 300s)
  end
  Saga->>Saga: → LEDGER_POSTING
  Saga->>Led: postJournal(idempotencyKey=saga-{id}-ledger)
  opt zdrojový účet přítomen
    Saga->>Saga: → FUNDS_CAPTURED
    Saga->>Bal: debit(zdroj) + releaseHold
  end
  opt cílový účet přítomen
    Saga->>Bal: credit(cíl, amount)
  end
  Saga->>Saga: → COMPLETED
  Note over Saga,Led: Při jakékoli výjimce → COMPENSATING:<br/>reverze journalu, vrácení zachyceného debetu,<br/>uvolnění holdu → COMPENSATED
```

Klíčové invarianty:
- **Idempotentní vstup** — `startSaga` vrátí existující ságu pro známý `idempotencyKey`; zaúčtování v ledgeru má klíč `saga-{id}-ledger`.
- **TTL holdu jako pojistka** — hold nese TTL 300 s, takže balance-service jej expiruje i když `releaseHold` selže.
- **Kompenzace vrací na kapsu** — samotná reverze journalu by peníze na zaúčtovaný zůstatek nevrátila, proto se zachycený debet explicitně připíše zpět (idempotency tag `compensation-{txId}`).
- **Příchozí kredit bez zdrojového účtu** přeskočí etapy rezervace prostředků a zaúčtuje rovnou do ledgeru.

## Outbox tok

```mermaid
sequenceDiagram
  participant TS as TransactionService
  participant DB as PostgreSQL
  participant D as TransactionOutboxDispatcher
  participant K as Kafka

  TS->>DB: BEGIN TX
  TS->>DB: INSERT INTO transactions
  TS->>DB: INSERT INTO transaction_outbox (TransactionInitiated, PENDING)
  TS->>DB: COMMIT
  Note over TS: sága běží → COMPLETED/FAILED →<br/>druhý outbox řádek (Completed/Failed)

  loop @Scheduled každých 5s (SKIP pokud běží)
    D->>DB: listProcessable(dávka 25)
    D->>K: publishWithResilience (CircuitBreaker + Retry + Timeout + Bulkhead)
    D->>DB: markSent / markFailed
  end
```

**Proč outbox:** transakční konzistence mezi zápisem do DB a publikací do Kafky. Doručení at-least-once; dispatcher obaluje každou publikaci do SmallRye Fault Tolerance (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`) a nikdy nenechá scheduler spadnout.

## Klíčové porty

| Port (application/port/out) | Adaptér | Účel |
|---|---|---|
| `TransactionRepository` | `PanacheTransactionRepository` | atomicky uloží transakci + outbox řádek |
| `PaymentSagaRepository` | `PanachePaymentSagaRepository` | persistence stavu ságy |
| `TransactionOutboxPort` / `TransactionOutboxRepository` | `TransactionOutboxRepositoryImpl` | zařazení / dispatch outboxu |
| `TransactionEventPublisher` | `LoggingTransactionEventPublisher` | publikace do Kafky + sestavení payloadu |
| `BalanceCoverPort` | `BalanceCoverClient` | hold / debet / kredit / uvolnění na balance-service |
| `FxRatePort` | `FxRateClient` | FX kurz pro zúčtování v jiné měně |

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.domain.money.Money` + `CurrencyCode` | částky, převod zúčtování, validace měny |
| `libs.domain.saga.SagaStateMachine` + `SagaTransitionPolicy` | hlídání přechodů platební ságy (ADR-0045) |
| `libs.api.pagination.CursorPage` / `CursorEncoder` / `PageInfo` | cursor stránkování `listTransactions` |
| `libs.persistence.outbox` | primitiva entity / repozitáře outboxu |
| `libs.security.Roles` | konstanty rolí pro `@RolesAllowed` |
| `libs.web.ServiceInfoResource` | `/api/v1/info` build metadata |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |
| `CommonExceptionMappers` | `IllegalArgumentException`→400, `IllegalStateException`→422 |

## Principy

1. **Hranice agregátu = Transaction** — jedna transakce, jedna sága, jedno referenční číslo.
2. **Synchronní sága, asynchronní události** — pohyb peněz je v rámci požadavku a konzistentní; události životního cyklu se šíří přes outbox + Kafku.
3. **Žádné podvojné účetnictví zde** — GL žije v ledger-service; tato služba zaúčtovává a reverzuje journaly přes fault-tolerant klienta.
4. **Idempotence end-to-end** — `idempotencyKey` volajícího, unique DB constraint, klíč zaúčtování v ledgeru, tag kompenzačního vrácení.
5. **Čistota domény** — přechody ságy a pravidla datumu zúčtování jsou čistá doménová logika bez frameworku.
