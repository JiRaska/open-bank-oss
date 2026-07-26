# Overview

## What the service does

`openbank-ledger-service` is the **double-entry general ledger** of the OpenBank platform — the golden source of accounting truth ([ADR-0039](../../../../docs/adr/0039-ledger-as-golden-source-balance-as-projection.md)). It holds:

- **GlAccount** — the chart of accounts: code, name, type (ASSET / LIABILITY / EQUITY / INCOME / EXPENSE), single currency, parent/leaf hierarchy. Seeded with cash, customer-deposit, interest, fee, per-currency deposit-control (2100/2101/2102/2103), FX position (199x) and FX counter-value (199x-CV) accounts.
- **JournalEntry** — a balanced, immutable accounting document: a transaction reference, entry/value date, status (PENDING / POSTED / REVERSED), and **two or more journal lines** that must balance **within each currency** (ADR-0025 per-currency balancing).
- **JournalLine** — one debit or credit leg: GL account, side, amount + currency, FX rate, base amount + base currency, sequence, and an optional **sub_account_id** (the per-customer sub-ledger dimension on deposit-control legs, ADR-0039 Phase B).
- **TrialBalance** — debit/credit totals per GL account that must net to zero.

It also runs the **daily FX revaluation** (mark-to-ČNB of foreign FX positions, [ADR-0046](../../../../docs/adr/0046-daily-fx-revaluation-mechanics-and-cnb-rates.md)).

## What the service **does NOT** do

- ❌ Does not compute or serve customer-facing balances — that's `balance-service`, a read-model projection (ADR-0039).
- ❌ Does not orchestrate payments or sagas — `transaction-service` does, then posts a balanced journal here.
- ❌ Does not hold the account definition / IBAN — `account-service`.
- ❌ Does not run AML/sanctions screening — `aml-service` / `sanctions-service`.
- ❌ Does not source FX rates — it reads the statutory ČNB fixing from `fx-service`.

## Position in the domain

```
   ┌────────────────────┐  POST balanced journal   ┌──────────────────┐
   │ transaction-service│ ───────────────────────► │  ledger-service  │
   └────────────────────┘   (PostJournalCommand)    └────────┬─────────┘
                                                             │
   ┌────────────────────┐  GET ČNB rate (REST)               │ outbox → Kafka
   │     fx-service      │ ◄────────────────────────────────┐│  openbank.ledger.journal.posted
   └────────────────────┘                                   ││
                                                             ▼▼
   ┌─────────────────┐                              ┌──────────────────────┐
   │   PostgreSQL    │ ◄── partitioned journal ──   │ balance-service      │
   │ (openbank_ledger│                              │ audit-service        │
   │  partitioned by │                              │ reconciliation/recon │
   │   entry_date)   │                              └──────────────────────┘
   └─────────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Post a balanced journal entry | `POST /api/v1/journals` | `JournalPosted` → `openbank.ledger.journal.posted` |
| Reverse a posted entry | `POST /api/v1/journals/{journalId}/reverse` | `JournalReversed` |
| List journal entries (cursor-paginated) | `GET /api/v1/journals` | — |
| Get journal entry by ID | `GET /api/v1/journals/{journalId}` | — |
| Get journals for a transaction | `GET /api/v1/journals/transaction/{transactionId}` | — |
| Trial balance (debit/credit per GL account) | `GET /api/v1/journals/trial-balance` | — |
| Per-customer sub-ledger balances | `GET /api/v1/journals/sub-ledger-balances` | — |
| Daily FX revaluation (ops/backfill trigger) | `POST /api/v1/ledger/fx-revaluation` | `FxRevalued` → `openbank.ledger.fx.revalued` |

## Callers

- **transaction-service** (`ROLE_OPERATOR`/`ROLE_API`) — posts the balanced journal for every settled transaction; the only writer of business postings.
- **balance-service** (`ROLE_API`) — reads the ledger / sub-ledger for reconciliation against the balance read-model (ADR-0039).
- **audit-service** — consumes the `JournalPosted` event stream for the tamper-evident audit chain.
- **admin-ui** (`ROLE_OPERATOR` / `ROLE_AUDITOR` / `ROLE_VIEWER`) — operators and auditors browse journals, trial balance, sub-ledger; operators trigger FX-revaluation backfill.
- **FxRevaluationScheduler** (in-process) — drives the daily revaluation automatically.

## Dependencies

- **PostgreSQL** (`openbank_ledger`) — partitioned journal, GL accounts, outbox, idempotency, partition audit.
- **Kafka** (`openbank-kafka`, topic `openbank.ledger.journal.posted`) — outbox dispatch.
- **fx-service** (REST, OIDC client filter) — statutory ČNB FX fixing for revaluation.
- **Keycloak** — OIDC auth (RS256 JWT).
- **openbank-libs** — Money / CurrencyCode, DomainEvent, CursorPage pagination, Roles, outbox plumbing, ServiceInfoResource, DocsResource, BuildInfo.

## Business value

- **Single source of accounting truth** — every money movement in the bank is a balanced double-entry posting here; the customer balance is merely a projection of it (ADR-0039).
- **Regulatory-grade integrity** — per-currency balancing (ADR-0025), immutable append-only journal with year partitioning and reversal-only correction, and a per-customer analytical sub-ledger (analytická evidence) that ties out the GL deposit-control accounts as required by CNB accounting law (563/1991 Sb. + decree 501/2002 Sb.).
- **At-least-once event propagation** via a transactional outbox (ADR-0050) so downstream balance/audit views stay eventually consistent.
- **Statutory FX valuation** — daily mark-to-ČNB revaluation of foreign positions, booking exchange-rate differences to a P&L account (ADR-0046).
