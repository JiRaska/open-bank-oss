# Overview

## What the service does

`openbank-balance-service` is the **authoritative source of balances** in the OpenBank platform. For every `(account_id, currency)` combination it maintains 4 amounts:

- **`booked_amount`** — booked (after settlement in the ledger)
- **`available_amount`** — available to spend (booked − reserved − pendingDebit + arranged_overdraft)
- **`reserved_amount`** — reserved by active holds (card authorisation, pending transfer)
- **`pending_amount`** — awaiting settlement (debit already accounted against available, not yet posted in ledger)

Plus an arranged overdraft:

- **`arranged_overdraft_limit`** — contractually agreed overdraft; the balance can be drawn down to this limit (arranged debet), beyond it is unarranged and the transaction is rejected (422 `insufficient-funds`).

## What the service **does NOT** do

- ❌ Does not open accounts (`account-service`)
- ❌ Does not execute transactions (`transaction-service`)
- ❌ Does not hold the double-entry book (`ledger-service` — technical GL accounts)
- ❌ Does not compute interest (`interest-service`)

## Position in the domain

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

## Key use cases

| Use case | API | Balance state |
|---|---|---|
| Read balance | `GET /api/v1/balances/{accountId}` | snapshot |
| Create hold (card authorisation) | `POST /api/v1/balances/{accountId}/holds` | reserved+, available− |
| Release hold (authorisation expired) | `DELETE /api/v1/balances/holds/{holdId}` | reserved−, available+ |
| Capture hold (card transaction settled) | `POST /api/v1/balances/holds/{holdId}/capture` | reserved−, pending+ |
| Apply transaction (event-driven) | (Kafka consumer) | booked±, pending± |
| Set arranged overdraft | `PATCH /api/v1/balances/{accountId}/overdraft` | arranged_overdraft_limit, available+ |

## Inputs

- **Kafka** `openbank.transaction.events` — settled credits / debits
- **REST** `account-service` (after `account.opened`) — initialise a new 0 EUR balance
- **REST** payment / card services — holds and captures
- **REST** compliance ops — set arranged overdraft

## Consumers of our events (`openbank.balance.events`)

- `account-service` — denormalised cache balance for the UI
- `notification-service` — `balance.low.v1` event, push to the client
- `fraud-detection` (planned) — anomaly detection on change-rate

## Business value

- **Consistent balance** — single service, single truth. No two screens will ever show different balances.
- **Real-time** — the Kafka consumer applies a transaction in tens of milliseconds.
- **Audit trail** — outbox + audit-service hold the history of every change.
- **Optimistic locking** (`version` column) → safe concurrent authorisations.
- **AnaCredit compatible** — arranged vs unarranged overdraft distinguished exactly per the ČNB methodology.
