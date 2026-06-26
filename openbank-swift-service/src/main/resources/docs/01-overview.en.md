# Overview

## What the service does

`openbank-swift-service` is the **system of record for SWIFT MT messages** on the OpenBank platform. It holds a single aggregate:

- **SwiftMessage** — a SWIFT MT/MX instruction with its BIC routing (`senderBic`, `receiverBic`), references (`transactionReference` field 20, `relatedReference` field 21), `valueDate` (YYYYMMDD), `currency` + `amountMinorUnits`, ordering customer and beneficiary, `chargeCode` (OUR/SHA/BEN field 71A), `priority` (NORMAL/URGENT/SYSTEM), the raw MT text (`rawMt`) and a lifecycle `status`.

Supported message types: **MT103** (single customer credit transfer), **MT202** (general financial-institution transfer), **MT900** (debit confirmation), **MT910** (credit confirmation), **MT940/MT950** (statements), **MT199** (free format).

The status lifecycle is: `PENDING → VALIDATED → SENT → ACKNOWLEDGED | REJECTED`, with `FAILED` for internal processing failures. On submit, the service runs a domain validation (BIC length 8..11, non-blank transaction reference, positive amount, valid charge code) and persists the message as `VALIDATED`.

## What the service **does NOT** do

- ❌ Does not initiate or authorize the underlying payment — the payment services and SCA own that.
- ❌ Does not keep the double-entry book — that is `ledger-service`.
- ❌ Does not recompute or hold balances — `balance-service` / `account-service`.
- ❌ Does not perform sanctions/AML screening — `sanctions-service` / `aml-service` screen upstream before release (see threat model residual risks).
- ❌ Does not own a physical connection to the SWIFT network in this codebase — dispatch is modelled via the outbox/event flow; the network gateway is an external entity.

## Position in the domain

```
   ┌────────────┐  POST /api/v1/swift   ┌──────────────────┐
   │ payments / │ ───────────────────►  │  swift-service   │
   │ operators  │  ack / reject         │  (Quarkus)       │
   └────────────┘ ───────────────────►  └────────┬─────────┘
                                                  │ outbox → Kafka
                                                  ▼
   PostgreSQL (openbank_swift)        ┌────────────────────────────┐
   swift_messages / swift_outbox      │ transaction-service        │
                                      │ aml-service / audit-service│
                                      │ (downstream consumers)     │
                                      └────────────────────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Submit a SWIFT message for dispatch | `POST /api/v1/swift` | (drained via `swift_outbox` → `openbank.payments.swift.event`) |
| Get a message by id | `GET /api/v1/swift/{id}` | — |
| List messages by status | `GET /api/v1/swift/status/{status}` | — |
| List all messages | `GET /api/v1/swift/messages` | — |
| Acknowledge a message (ACK from receiving bank) | `POST /api/v1/swift/{id}/ack` | status → `ACKNOWLEDGED` |
| Reject a message | `POST /api/v1/swift/{id}/reject` | status → `REJECTED` |

> Note: the send use case currently persists the aggregate; the explicit write into `swift_outbox` from the send path is not yet wired in the use-case code — see [02 — Architecture](./02-architecture.md). The outbox dispatcher and Kafka publisher are implemented and operational.

## Callers

- **payment services / operators** (via Keycloak token) — submit, acknowledge, reject SWIFT messages.
- **SWIFT gateway / counterparty** — inbound ACK / reject (modelled as authenticated calls to the ack/reject endpoints; gateway identity to be pinned via mTLS allow-list per threat model).
- **admin-ui** — read views, status queries for the payments cockpit.

## Dependencies

- **PostgreSQL** (database `openbank_swift`, logical schema `swift_schema`)
- **Kafka** (channel `swift-events-out`, topic `openbank.payments.swift.event`)
- **Redis (Valkey)** — client wired for caching/idempotency support
- **Keycloak** — OIDC auth (client `openbank-services`)
- **OPA sidecar** — authorization decisions (ADR-0034), advisory by default
- **openbank-libs** — `libs.authz` (`@Authorize`, `OpaSidecarPolicyDecisionPoint`), service-info/docs plumbing, build metadata

## Business value

- **Single source of truth** for the state of every outbound/inbound SWIFT instruction — no duplicate wire records across services.
- **Auditable lifecycle** — each create/ack/reject is a discrete, traceable state transition on a high-value-fraud-sensitive surface.
- **Transactional outbox** — at-least-once event propagation to downstream consumers (transaction, AML, audit) without dual-write inconsistency.
- **Compliance-ready** — money-path classification with a maintained threat model, OPA-gated actions, and security headers enforced at the edge.
