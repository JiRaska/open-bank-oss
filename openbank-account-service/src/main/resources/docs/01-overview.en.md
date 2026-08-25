# Overview

## What the service does

`openbank-account-service` is the **system of record for account definitions** in the OpenBank platform. It holds:

- **Account aggregate** — IBAN, currency, owner (party-id), account type (CURRENT / SAVINGS / TERM_DEPOSIT / TECHNICAL), state (ACTIVE / FROZEN / CLOSED).
- **AccountAuthorization** — who has which permissions on the account (OWNER / SIGNATORY / VIEWER / TECHNICAL).
- **AccountBalance** — a denormalized "fast" balance for the UI; **the authoritative source is `openbank-balance-service`** (event-driven sync).

## What the service **does NOT** do

- ❌ Does not compute balances from transactions — that's `balance-service` reading from `transaction-service`.
- ❌ Does not maintain a double-entry book — that's `ledger-service` (technical GL accounts).
- ❌ Does not execute payments — `payment` services create a transaction, it posts in the ledger, balance is recomputed.
- ❌ Does not issue cards — `card-issuance-service`.
- ❌ Does not run KYC/AML at account opening — `kyc-service` / `aml-service` do, triggered by the `AccountOpened` event.

## Position in the domain

```
   ┌────────────┐  AccountOpened    ┌─────────────┐
   │   admin UI │ ───────────────►  │ kyc-service │
   └─────┬──────┘                   └─────────────┘
         │ POST /accounts
         ▼
   ┌─────────────────┐  outbox → Kafka  ┌────────────────┐
   │ account-service │ ───────────────► │ balance-service│
   └────┬────────────┘                  │ ledger-service │
        │                               │ audit-service  │
        ▼                               │ notification   │
    PostgreSQL                          └────────────────┘
   (schema: account)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Open account for a customer | `POST /api/v1/accounts` | `AccountOpened` |
| Freeze account (court order, AML) | `POST /api/v1/accounts/{id}/freeze` | `AccountFrozen` |
| Unfreeze account | `POST /api/v1/accounts/{id}/unfreeze` | `AccountUnfrozen` |
| Close account | `POST /api/v1/accounts/{id}/close` | `AccountClosed` |
| Add co-owner | `POST /api/v1/accounts/{id}/authorizations` | `AuthorizationGranted` |
| Find accounts for a party | `GET /api/v1/accounts?partyId=…` | — |

## Callers

- **admin-ui** (via Keycloak token) — operators, compliance
- **kyc-service** — read-only existence check during KYC review
- **payment services** (sepa, domestic, swift, …) — read-only IBAN validation and state check before a payment
- **balance-service** — read-only for initial balance setup of a new account

## Dependencies

- **PostgreSQL** (`openbank-postgres`, schema `account`)
- **Kafka** (`openbank-kafka`, topic `openbank.account.events.v1`)
- **Redis (Valkey)** — idempotency cache
- **Keycloak** — auth
- **openbank-libs** ≥ 0.1.0 — Money, Iban, AccountId, IdempotencyStore, outbox, BuildInfo, DocsResource

## Business value

- **Single source of truth** for the existence and state of an account — no duplicate account lists across services, only cached projections.
- **Audit trail** — every account operation emits domain events that `audit-service` persists for the 10-year statutory period.
- **Real-time propagation** via outbox + Kafka — downstream services (balance, notifications) have an eventually-consistent view in tens of milliseconds.
- **Compliance-ready** — freeze/unfreeze workflow for court orders, AML hold, sanctions hit.
