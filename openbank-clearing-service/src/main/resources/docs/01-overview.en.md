# Overview

## What the service does

`openbank-clearing-service` is the **clearing & settlement engine** of the OpenBank platform. It sits *after* payment origination and *before* (or alongside) ledger posting, and is responsible for grouping payments into settlement batches and driving them through a settlement cycle. It holds three aggregates:

- **ClearingItem** — a single payment submitted for clearing: `paymentId`, `paymentReference`, debtor/creditor IBAN + optional BIC, `amount`, `currency`, `rail`, `valueDate`, `endToEndId`, `remittanceInfo`, and a `status` (PENDING / IN_CLEARING / SETTLED / FAILED / REVERSED).
- **ClearingBatch** — a settlement cycle for a given payment rail: `batchReference`, `rail`, `settlementType` (GROSS / NET / DEFERRED_NET, default NET), aggregate `totalDebit` / `totalCredit` / `netPosition`, `currency`, `itemCount`, `cycleId`, `settlementDate`, `settledAt` and `status`.
- **SettlementPosition** — a per-participant net position within a cycle: `participantBic`, `currency`, `cycleId`, `grossDebit`, `grossCredit`, `netPosition`, `settled` flag.

Supported payment rails: `SEPA_SCT`, `SEPA_SCT_INST`, `SWIFT`, `DOMESTIC`, `INTERNAL`.

## What the service **does NOT** do

- ❌ Does not originate payments — `sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service` create the payment; they then submit it here.
- ❌ Does not keep account balances — `balance-service` is authoritative.
- ❌ Does not post double-entry bookings — `ledger-service` owns the GL; settlement results flow to `transaction-service` (declared downstream, relation `api`, role "settles").
- ❌ Does not run sanctions/AML screening — that is enforced upstream at the payment surfaces (ADR-0032 screening gate).
- ❌ Does not validate IBAN ownership or hold funds — clearing operates on already-authorised payment instructions.

## Position in the domain

```
   ┌──────────────────┐  POST /clearing/submit   ┌────────────────────┐
   │ payment services │ ───────────────────────► │ clearing-service   │
   │ (sct/inst/dom/   │                          │  batches / items / │
   │  swift)          │                          │  positions         │
   └──────────────────┘                          └─────────┬──────────┘
                                                            │
   ┌──────────────────┐  cycle/trigger, settle             │ outbox → Kafka
   │ operator / ops   │ ──────────────────────────────────►│ openbank.clearing.batch.event
   └──────────────────┘                                     ▼
                                                  ┌────────────────────┐
                                                  │ transaction-service│  (settles)
                                                  │ audit / downstream │
                                                  └────────────────────┘
                                                            │
                                                            ▼
                                                      PostgreSQL
                                                  (db: openbank_clearing)
```

## Key use cases

| Use case | API | Event / effect |
|---|---|---|
| Submit a payment to clearing | `POST /api/v1/clearing/submit` | new `ClearingItem` (status PENDING), placeholder batch id until cycle |
| Trigger a clearing cycle for a rail | `POST /api/v1/clearing/cycle/trigger?rail=…` | creates a `ClearingBatch`, attaches pending items, status IN_CLEARING (or SETTLED if empty) |
| Settle a batch | `POST /api/v1/clearing/batches/{id}/settle` | batch status → SETTLED, `publishBatchSettled` event |
| List / inspect batches | `GET /api/v1/clearing/batches`, `…/{id}`, `…/{id}/items` | read |
| Get settlement positions for a cycle | `GET /api/v1/clearing/positions/{cycleId}` | read |
| Look up a clearing item | `GET /api/v1/clearing/items/{id}`, `…/by-payment/{paymentId}` | read |

## Callers

- **payment services** (`sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`) — submit payments for clearing (`ROLE_API` / `ROLE_PAYMENTS`).
- **operations / payment-ops** (via admin UI, Keycloak token) — trigger cycles and settle batches (`ROLE_PAYMENTS` / `ROLE_ADMIN`).
- **admin-ui / viewers / operators** — read-only views of batches, items and positions.

## Dependencies

- **PostgreSQL** (database `openbank_clearing`, owned schema `clearing_schema` per governance.yaml)
- **Kafka** (topic `openbank.clearing.batch.event`, channel `clearing-events-out`)
- **Redis (Valkey)** — wired (`quarkus.redis.hosts`) for idempotency/caching
- **Keycloak** — OIDC auth
- **OPA sidecar** — `@Authorize` policy checks (ADR-0034), advisory by default
- **openbank-libs** — `libs.authz` (`@Authorize`, `OpaSidecarPolicyDecisionPoint`), `libs.security.Roles`, outbox plumbing, `DocsResource`, `ServiceInfoResource`

## Business value

- **Settlement aggregation** — turns a high volume of individual payment instructions into a small number of net settlement positions per participant, the basis of net settlement on payment rails.
- **Rail-aware processing** — one engine for SEPA SCT, SEPA Instant, domestic and SWIFT, each with its own cycle.
- **Auditability** — batch settlement emits domain events via the transactional outbox, giving downstream services (transaction-service, audit) an eventually-consistent, replayable record.
- **Money-path discipline** — least-privilege roles, positive-amount DB constraints, and a maintained threat model protect a high-blast-radius operation (one settle/trigger touches many payments).
