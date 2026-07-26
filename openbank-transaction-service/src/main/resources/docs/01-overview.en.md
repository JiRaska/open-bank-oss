# Overview

## What the service does

`openbank-transaction-service` is the **system of record for transactions** in the OpenBank platform and the orchestrator that turns a payment request into a booked transaction. It holds:

- **Transaction aggregate** — reference number, type (DEBIT / CREDIT / TRANSFER / FEE / INTEREST / REVERSAL / ADJUSTMENT), source/target account, amount + currency, FX rate and settlement (base) amount, status (PENDING / PROCESSING / COMPLETED / FAILED / REVERSED), value/booking dates, and a rich set of BIAN / ISO 20022 search fields (IBAN, BBAN, end-to-end id, counterparty, purpose code, …).
- **PaymentSaga** — the per-transaction orchestration state machine (STARTED → PAYMENT_INITIATED → FUNDS_RESERVED → LEDGER_POSTING → FUNDS_CAPTURED → COMPLETED, with COMPENSATING / COMPENSATED / FAILED paths) that drives the distributed "money movement" across balance-service and ledger-service.

When a transaction is initiated it runs the saga synchronously: place a hold on the source pocket (balance-service), post the double-entry journal (ledger-service), capture the debit, credit the beneficiary pocket, and mark the transaction COMPLETED — or compensate (reverse the journal, refund the pocket, release the hold) on any failure.

## What the service **does NOT** do

- ❌ Does not keep the double-entry general ledger — that's `ledger-service` (this service *calls* it to post a journal).
- ❌ Does not compute or hold authoritative balances — that's `balance-service` (this service places holds / debits / credits against it).
- ❌ Does not own the FX rate — it reads rates from `fx-service` for cross-currency settlement.
- ❌ Does not speak any payment scheme — `sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`, `standing-order-service`, `clearing-service` translate scheme messages and create transactions here.
- ❌ Does not run AML/sanctions screening itself — screening is gated upstream by the payment services (the `aml_screened` column records the outcome for audit).

## Position in the domain

```
   ┌──────────────────────┐  POST /transactions   ┌──────────────────────┐
   │ payment services     │ ────────────────────► │ transaction-service  │
   │ sepa / domestic /    │                        │  (saga orchestrator) │
   │ swift / instant / SO │                        └──────────┬───────────┘
   └──────────────────────┘                                   │
            ▲ fx-service (rates)                               │ sync calls
            │                                                  ▼
            │                          ┌────────────────────────────────────┐
            │                          │ balance-service  (hold/debit/credit)│
            │                          │ ledger-service   (post/reverse GL)  │
            │                          └────────────────────────────────────┘
            │
   ┌────────┴─────────┐  outbox → Kafka   ┌──────────────────────┐
   │ transaction-     │ ────────────────► │ audit-service        │
   │ service          │                   │ notification-service │
   └──────┬───────────┘                   └──────────────────────┘
          ▼
     PostgreSQL
   (openbank_transactions, schema-owned `transactions_schema`)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Initiate a transaction (drives the payment saga) | `POST /api/v1/transactions` | `openbank.transactions.transaction.initiated` then `.completed` or `.failed` |
| List an account's transactions (cursor-paginated) | `GET /api/v1/transactions?accountId=…` | — |
| Search transactions (IBAN/BBAN/reference/amount/date/counterparty) | `GET /api/v1/transactions/search` | — |
| Get one transaction by id | `GET /api/v1/transactions/{transactionId}` | — |

## Callers

- **payment services** (`sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`, `standing-order-service`, `clearing-service`) — initiate transactions (`ROLE_API`/`ROLE_OPERATOR`).
- **fx-service** — upstream rate source for cross-currency settlement (this service calls *out* to it).
- **agent-service** — read-only MCP tool over transaction history (`ROLE_API`).
- **admin-ui** — operators / compliance read transaction history and search.

## Dependencies

- **PostgreSQL** (`openbank_transactions`, owns `transactions_schema`)
- **Kafka** (topic `openbank.transactions.transaction.initiated` and sibling event types)
- **ledger-service** (REST, fault-tolerant client `LedgerCallGuard`) — post / reverse journal
- **balance-service** (REST) — place hold, debit, credit, release hold
- **fx-service** (REST) — FX rate for cross-currency settlement
- **Keycloak** — auth (OIDC); `oidc-client` for service-to-service tokens
- **openbank-libs** — `Money`/`CurrencyCode`, `CursorPage`/`CursorEncoder` pagination, `SagaStateMachine` (ADR-0045), outbox primitives, `Roles`, `ServiceInfoResource`, `DocsResource`

## Business value

- **Single source of truth** for what was transacted — every payment, fee, interest posting and reversal lands here with a stable reference number and audit trail.
- **Atomic money movement** — the payment saga keeps balance pockets and the ledger consistent, compensating cleanly on partial failure so a customer is never left short.
- **Regulator-grade history** — a partitioned, 7-year-retained transaction store with ISO 20022 / BIAN search fields for CNB reporting, dispute handling and Open Banking history.
- **Eventual propagation** via outbox + Kafka — audit and notification consumers see every lifecycle event within seconds.
