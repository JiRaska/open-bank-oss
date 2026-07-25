# Overview

## What the service does

`openbank-card-issuance-service` is the **system of record for cards** in the OpenBank platform. It holds:

- **Card aggregate** — the card's `id`, the owning `partyId` and `accountId`, `productCode`, `cardType` (DEBIT / CREDIT / PREPAID / VIRTUAL), `network` (VISA / MASTERCARD / AMEX / UNIONPAY), a **masked PAN** (last 4 digits only), cardholder and embossed name, expiry date, status, per-card spend limits (daily / monthly, in minor units), currency and optional delivery address.
- **Lifecycle state machine** — a card with plastic is issued `PENDING` and activated when the customer receives it; a `VIRTUAL` / `SINGLE_USE` card has nothing to receive and is issued `ACTIVE` directly. Then `PENDING → ACTIVE` (activate), `ACTIVE → SUSPENDED` (suspend) / `SUSPENDED → ACTIVE` (resume), `{ACTIVE, SUSPENDED} → BLOCKED` (permanent block, requires a reason). `EXPIRED` and `CANCELLED` are terminal states in the model.
- **Domain events** — `CardIssued` and `CardStatusChanged`, emitted on every state change and published via the transactional outbox.

## What the service **does NOT** do

- ❌ Does not authorize card payments at the POS/ATM — there is no card-authorization/switch component here.
- ❌ Does not store a full PAN, CVV/CVC, or PIN — only a masked PAN (`**** **** **** 1234`) is persisted (PCI DSS scope minimisation).
- ❌ Does not personalise/emboss or physically produce the plastic — that is a card-vendor concern downstream of the `CardIssued` event.
- ❌ Does not open accounts or hold balances — `account-service` / `balance-service` do.
- ❌ Does not run KYC/AML — `kyc-service` / `aml-service` do; the card is issued against an already-onboarded party.

## Position in the domain

```
   ┌────────────┐  POST /api/v1/cards   ┌──────────────────────┐
   │  admin UI  │ ───────────────────►  │ card-issuance-service│
   └────────────┘                       └──────────┬───────────┘
                                                    │
   ┌────────────────┐  block (dispute)             │ outbox → Kafka
   │ dispute-service│ ───────────────────────────► │ (openbank.cards.events)
   └────────────────┘                              │
                                                    ▼
                                       ┌────────────────────────┐
                                       │ PostgreSQL              │
                                       │ (db: openbank_cards)    │
                                       └────────────────────────┘
                                                    │
                                    card.issued.v1 / card.status_changed.v1
                                                    ▼
                                       ┌────────────────────────┐
                                       │ audit / notification /  │
                                       │ downstream consumers    │
                                       └────────────────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Issue a new card for a party + account | `POST /api/v1/cards` | `card.issued.v1` |
| Activate a pending card | `POST /api/v1/cards/{id}/activate` | `card.status_changed.v1` |
| Suspend a card temporarily | `POST /api/v1/cards/{id}/suspend` | `card.status_changed.v1` |
| Resume a suspended card | `POST /api/v1/cards/{id}/resume` | `card.status_changed.v1` |
| Block a card permanently (lost/stolen/dispute) | `POST /api/v1/cards/{id}/block` | `card.status_changed.v1` |
| Get a card by id | `GET /api/v1/cards/{id}` | — |
| List cards for an account | `GET /api/v1/cards/account/{accountId}` | — |
| List cards for a party | `GET /api/v1/cards/party/{partyId}` | — |
| List all cards | `GET /api/v1/cards` | — |

## Callers

- **admin-ui** (via Keycloak token) — operators issue, activate and manage cards; compliance can block.
- **dispute-service** — triggers a block when a fraud/chargeback dispute requires it (declared upstream in `governance.yaml`).
- Downstream **event consumers** (audit, notification, and any card-vendor integration) — read-only, via Kafka.

## Dependencies

- **PostgreSQL** (`openbank_cards` database, schema `cards_schema` per `governance.yaml`)
- **Kafka** (topic `openbank.cards.events`)
- **Redis (Valkey)** — present in the stack (Redis client configured); idempotency for issue is enforced at the DB level via the unique `idempotency_key` column.
- **Keycloak** — OIDC auth
- **openbank-libs** — shared runtime plumbing (BuildInfo, ServiceInfoResource, DocsResource, outbox helpers)

## Business value

- **Single source of truth** for the existence and state of every card — no duplicate card lists across services.
- **PCI scope minimisation** — by storing only a masked PAN and never the CVV/PIN, the service stays out of the high-cost PCI DSS cardholder-data environment.
- **Auditable lifecycle** — every transition emits a domain event carrying the actor (`changedBy`) and reason, persisted downstream for the statutory retention period.
- **Real-time propagation** via outbox + Kafka — downstream systems (notifications, card vendor, audit) get an eventually-consistent view within seconds.
