# Overview

## Co služba dělá

`openbank-balance-service` je **autoritativní zdroj zůstatků** v OpenBank platformě. Pro každou kombinaci `(account_id, currency)` udržuje 4 částky:

- **`booked_amount`** — zaúčtováno (po settlement v ledger)
- **`available_amount`** — k dispozici (booked − reserved − pendingDebit + arranged_overdraft)
- **`reserved_amount`** — zarezervováno aktivními holdy (autorizace karty, pending transfer)
- **`pending_amount`** — čeká na settlement (debit již zaúčtován proti available, ale v ledger ještě nedošel)

A povolený debet:

- **`arranged_overdraft_limit`** — kontokorent, smluvně dohodnutý; pod tuto hranici lze čerpat (povolený debet), níž je nepovolený a transakce se odmítne (422 `insufficient-funds`).

## Co služba **nedělá**

- ❌ Nezakládá účty (`account-service`)
- ❌ Nevykonává transakce (`transaction-service`)
- ❌ Nedrží double-entry book (`ledger-service` — technické GL účty)
- ❌ Nepočítá úroky (`interest-service`)

## Pozice v doméně

```
   ┌──────────────────┐   transaction.committed   ┌──────────────────┐
   │transaction-service│ ─────────────────────►   │ balance-service  │
   └──────────────────┘    (Kafka consumer)        └──┬───────────────┘
                                                     │ outbox
                                                     ▼
   ┌──────────────────┐   balance.updated         ┌──────────────────┐
   │  account-service │ ◄──────────────────────── │     Kafka        │
   │ (denorm cache)   │                            │openbank.balance  │
   └──────────────────┘                            └──┬───────────────┘
                                                     │
                                       ┌─────────────┴─────────────┐
                                       ▼                           ▼
                              notification-service          fraud-detection
                              (low-balance alert)
```

## Klíčové use-cases

| Use-case | API | Stav balance |
|---|---|---|
| Načti zůstatek | `GET /api/v1/balances/{accountId}` | snapshot |
| Vytvoř hold (autorizace karty) | `POST /api/v1/balances/{accountId}/holds` | reserved+, available− |
| Uvolni hold (autorizace expirovala) | `DELETE /api/v1/balances/holds/{holdId}` | reserved−, available+ |
| Captures hold (karta dotažena) | `POST /api/v1/balances/holds/{holdId}/capture` | reserved−, pending+ |
| Aplikuj transakci (event-driven) | (Kafka consumer) | booked±, pending± |
| Nastav arranged overdraft | `PATCH /api/v1/balances/{accountId}/overdraft` | arranged_overdraft_limit, available+ |

## Vstupy

- **Kafka** `openbank.transaction.events` — settled credits / debits
- **REST** `account-service` (po `account.opened`) — inicializace nového zůstatku 0 EUR
- **REST** payment / card služby — holdy a captures
- **REST** compliance ops — nastavení arranged overdraft

## Konzumenty našich eventů (`openbank.balance.events`)

- `account-service` — denormalizovaný cache balance pro UI
- `notification-service` — `balance.low.v1` event, push klientovi
- `fraud-detection` (plánováno) — anomaly detection nad rychlostí změn

## Hodnota pro byznys

- **Konzistentní zůstatek** — jediný service, jediná pravda. Žádné dvě obrazovky neuvidí různé zůstatky.
- **Real-time** — Kafka consumer aplikuje transakci do desítek ms.
- **Audit-trail** — outbox + audit-service drží historii každé změny.
- **Optimistic locking** (`version` column) → safe paralelní authorize.
- **AnaCredit kompatibilní** — povolený vs nepovolený debet rozlišený přesně dle ČNB metodiky.
