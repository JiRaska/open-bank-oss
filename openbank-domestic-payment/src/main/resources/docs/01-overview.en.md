# Overview

## What the service does

`openbank-domestic-payment` is the **initiation and lifecycle owner for Czech domestic payments**. It:

- **Accepts a domestic payment instruction** — debtor account (id + account number + bank code), creditor account number + bank code, amount, currency, and the Czech payment symbols (variable / specific / constant), an optional message for payee, priority (STANDARD / URGENT), transfer scope (OWN_ACCOUNTS / INTERNAL_CLIENT / TECHNICAL_ACCOUNT), and an end-to-end id.
- **Screens the payment synchronously** against the sanctions lists at create time (ADR-0032): both debtor and creditor names are checked. A clean payment is released to `VALIDATED`, a sanctions hit is `REJECTED` with reason `SANCTIONS_HIT` and an AML case opened, and a sub-threshold potential hit or a screening-service outage holds the payment in `RECEIVED` for human review (fail-closed).
- **Drives the payment state machine** — `RECEIVED → VALIDATED → SENT_TO_CLEARING → SETTLED`, with `REJECTED` / `RETURNED` / `CANCELLED` terminal branches — and emits a domain event on every transition.
- **Publishes domain events** via a transactional outbox so downstream clearing/ledger/audit consumers observe the payment lifecycle.

## What the service **does NOT** do

- It does not clear or settle the payment — `clearing-service` does the inter-bank clearing; this service only marks `SENT_TO_CLEARING` / `SETTLED`.
- It does not post the double-entry accounting — `ledger-service` does.
- It does not handle SEPA or cross-border payments — `sepa-payment` / `sepa-instant` / `swift-service` do.
- It is not the sanctions/AML authority — it calls `sanctions-service` for the screening decision and `aml-service` to open a case; the policy decision data lives there.
- It does not perform SCA — Strong Customer Authentication is expected upstream for customer-initiated payments (PSD2 RTS; `sca_reference` is recorded).

## Position in the domain

```
   ┌────────────┐  POST /domestic-payments   ┌─────────────────────┐
   │ channels / │ ─────────────────────────► │ sanctions-service   │
   │ operators  │                            │ (sync screen)       │
   └─────┬──────┘                            └─────────────────────┘
         │ POST /api/v1/domestic-payments          ▲   │ HIT/POTENTIAL
         ▼                                          │   ▼
   ┌──────────────────────────┐  AML case  ┌─────────────────────┐
   │ domestic-payment-service │ ─────────► │ aml-service         │
   └────┬─────────────────────┘            └─────────────────────┘
        │ outbox → Kafka
        │ (openbank.domestic.payment.events)
        ▼
   ┌─────────────────┐   ┌──────────────────────────────────┐
   │ PostgreSQL      │   │ clearing-service / ledger-service │
   │ (domestic DB)   │   │ audit-service / notification      │
   └─────────────────┘   └──────────────────────────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Create a domestic payment (screened on create) | `POST /api/v1/domestic-payments` | `domestic.payment.created` |
| Get a payment by id | `GET /api/v1/domestic-payments/{paymentId}` | — |
| List payments (by status / debtor account) | `GET /api/v1/domestic-payments` | — |
| Transition payment status (validate / send / settle / reject / cancel) | `PATCH /api/v1/domestic-payments/{paymentId}/status` | `domestic.payment.status-changed` |

## Callers

- **payment channels / operators** (via Keycloak token, roles `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`) — create and drive payments.
- **admin-ui / compliance ops** — read payment detail and lists, manual status transitions, review held payments.
- **other services** (`ROLE_API`) — read-only list access.

## Dependencies

- **PostgreSQL** (database `openbank_domestic_payments`)
- **Kafka** (topic `openbank.domestic.payment.events`)
- **Redis (Valkey)** — four-eyes approval workflow state; create-payment idempotency is durable in PostgreSQL
- **Keycloak** — OIDC auth
- **sanctions-service** (REST client `sanctions-service`) — synchronous name screening (ADR-0032)
- **aml-service** (REST client `aml-service`) — open AML case on hit / review / screening outage
- **OPA sidecar** (ADR-0034) — `@Authorize` advisory authz
- **openbank-libs** — approval store, outbox plumbing, `ApiError`/`ErrorCode`, `@Authorize`, `ServiceInfoResource`, `DocsResource`

## Business value

- **Sanctions-safe by construction** — no domestic payment is released without passing the synchronous screening gate; outages fail closed rather than letting a payment through.
- **Auditable lifecycle** — every state change emits a domain event persisted by `audit-service`, satisfying repudiation and incident-evidence requirements.
- **Transactional consistency** — the payment row and its outbox event are written in one DB transaction, so downstream clearing/ledger never see a payment the database does not have.
- **CZ-rails native** — first-class support for Czech payment symbols, bank codes, and CNB reporting fields.
