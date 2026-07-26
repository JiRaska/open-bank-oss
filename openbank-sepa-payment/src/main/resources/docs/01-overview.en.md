# Overview

## What the service does

`openbank-sepa-payment` is the **lifecycle owner of SEPA Credit Transfers** (SCT, and the SCT_INST type marker) in the OpenBank platform. It holds:

- **SepaPayment aggregate** — payment id, idempotency key, type (`SCT` / `SCT_INST`), status, debtor (account id, IBAN, name), creditor (IBAN, name, BIC), amount + currency, remittance info, end-to-end id, reject reason/detail, and compliance fields (purpose code, charge bearer, SCA reference, consent id, AML screening flags).
- **Status lifecycle** — a guarded state machine `RECEIVED → VALIDATED → PROCESSING → COMPLETED` with terminal branches `REJECTED` / `RETURNED` / `CANCELLED`. Invalid transitions are rejected by the domain model.
- **Synchronous screening gate** (ADR-0032) — on create, debtor and creditor names are screened against the sanctions lists; a clean payment becomes `VALIDATED`, a hit is `REJECTED` (`SANCTIONS_HIT`) with an AML case opened, and a sub-threshold potential hit or a screening-service outage holds the payment in `RECEIVED` for human review (fail-closed).

## What the service **does NOT** do

- Does not run instant (SCT Inst) settlement timing — that is `openbank-sepa-instant`.
- Does not process domestic (non-SEPA) transfers — `openbank-domestic-payment`.
- Does not clear/settle the payment — `openbank-clearing-service` consumes the events.
- Does not post double-entry bookings — `openbank-ledger-service`.
- Does not compute or hold balances — `openbank-balance-service`.
- Does not maintain the sanctions lists or AML cases — it **calls** `openbank-sanctions-service` (screen) and `openbank-aml-service` (open case).
- Does not perform SCA itself — SCA evidence is referenced (`sca_reference`); the decoupled approval flow lives in `openbank-sca-service` (ADR-0021).

## Position in the domain

```
   ┌────────────┐   POST /sepa-payments   ┌────────────────────┐  screen (sync)  ┌───────────────────┐
   │  admin UI  │ ──────────────────────► │  sepa-payment      │ ──────────────► │ sanctions-service │
   │  channels  │                         │  service           │                 └───────────────────┘
   └────────────┘                         │                    │  open case      ┌───────────────────┐
                                          │                    │ ──────────────► │   aml-service     │
                                          └──────┬─────────────┘                 └───────────────────┘
                                                 │ outbox → Kafka
                                                 ▼  (openbank.sepa.payment.events)
                                          ┌──────────────────────────────────────┐
                                          │ clearing-service / ledger-service     │
                                          │ audit-service / notification          │
                                          └──────────────────────────────────────┘
                                                 │
                                                 ▼
                                          PostgreSQL (openbank_sepa_payments)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Create a SEPA payment (with synchronous screening) | `POST /api/v1/sepa-payments` | `sepa.payment.created` (+ `sepa.payment.status-changed` on the screening verdict) |
| Get a payment by id | `GET /api/v1/sepa-payments/{paymentId}` | — |
| List payments (filter by status / debtor account) | `GET /api/v1/sepa-payments` | — |
| Transition payment status (operations/clearing) | `PATCH /api/v1/sepa-payments/{paymentId}/status` | `sepa.payment.status-changed` |

## Callers

- **admin-ui / payment-initiating channels** (via Keycloak token) — operators with `ROLE_PAYMENTS` / `ROLE_OPERATOR` initiate transfers.
- **clearing / ledger pipeline** — consume the emitted Kafka events (not direct REST callers).
- **operations / clearing back-office** — drive status transitions (`PROCESSING` → `COMPLETED` / `RETURNED`).
- **service callers** — `ROLE_API` may list payments.

## Dependencies

- **PostgreSQL** (database `openbank_sepa_payments`, declared schema `sepa_schema`)
- **Kafka** (topic `openbank.sepa.payment.events`)
- **Redis (Valkey)** — idempotency cache (`libs.idempotency.IdempotencyStore`)
- **Keycloak** — OIDC auth
- **sanctions-service** (REST, synchronous `POST /api/v1/sanctions/screen`) — screening gate, **fail-closed**
- **aml-service** (REST, idempotent `POST /api/v1/aml/cases`) — best-effort case opening on hit/hold
- **OPA sidecar** (ADR-0034) — authorization, advisory by default (`AUTHZ_ENFORCE`)
- **openbank-libs** — IdempotencyStore, `@Authorize`, ApiError/ErrorCode, ServiceInfoResource, DocsResource, BuildInfo

## Business value

- **Single owner of the SCT lifecycle** — one consistent state machine for every SEPA credit transfer, with auditable transitions.
- **Screening before release** — sanctions/AML screening is enforced **before** the payment leaves `RECEIVED`, so no value-bearing instruction is released un-screened (ADR-0032, fail-closed).
- **Durable, at-least-once propagation** — the transactional outbox guarantees that every accepted payment and every status change is published to clearing/ledger/audit, even across crashes.
- **Idempotent initiation** — a retried create with the same `Idempotency-Key` never produces a duplicate transfer.
