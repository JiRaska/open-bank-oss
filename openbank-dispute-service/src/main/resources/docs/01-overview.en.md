# Overview

## What the service does

`openbank-dispute-service` is the **system of record for payment disputes and chargebacks** in the OpenBank platform. It holds:

- **Dispute aggregate** — a `reference` (`DSP-<epochMillis>`), the disputed `transactionId` + `accountId` + `partyId`, a `disputeType` (UNAUTHORIZED / DUPLICATE / GOODS_NOT_RECEIVED / NOT_AS_DESCRIBED / CREDIT_NOT_PROCESSED / TECHNICAL_ERROR / OTHER), a `status` lifecycle (OPEN → UNDER_REVIEW → PENDING_CUSTOMER / PENDING_MERCHANT → RESOLVED_* / WITHDRAWN / ESCALATED), a `resolution` (CHARGEBACK / REPRESENTMENT / ARBITRATION / WITHDRAWN / PENDING), the claimed `amount` + `currency`, optional merchant data and a `chargebackAmount`.
- **DisputeEvidence** — supporting items attached to a dispute (`evidenceType`, `description`, optional `fileReference`, `submittedBy`).
- **DisputeTimeline** — an append-only audit trail of events on the dispute (`OPENED`, `STATUS_CHANGED`, `EVIDENCE_ADDED`, …) with the acting `actor`.

A dispute carries an SLA: `resolutionDeadline = filingDate + resolution-sla-days` (default **45 days**); a chargeback filing window of **120 days** is configured.

## What the service **does NOT** do

- ❌ Does not move money or reverse a transaction — `transaction-service` / `ledger-service` perform any actual credit/refund.
- ❌ Does not clear chargebacks with an external card scheme network — there is no Mastercard/Visa scheme connector here.
- ❌ Does not decide fraud — fraud scoring / AML lives in the dedicated services; the dispute records the human/operator workflow.
- ❌ Does not store evidence files — it stores an evidence **reference** (`fileReference`), not the binary blob.
- ❌ Does not perform Strong Customer Authentication — `sca-service` owns SCA.

## Position in the domain

```
   ┌────────────┐   POST /disputes      ┌────────────────────┐
   │  admin UI  │ ───────────────────►  │  dispute-service   │
   │ (operator) │                       │  (this service)    │
   └────────────┘                       └─────────┬──────────┘
   ┌────────────┐   POST /evidence                │ outbox → Kafka
   │ customer   │ ──────────────────────────────► │ openbank.disputes.dispute.event
   │  app/API   │                                 ▼
   └────────────┘                       ┌────────────────────┐
                                        │ audit-service      │
        PostgreSQL                      │ notification       │
      (db: openbank_dispute)            │ card-issuance (blk)│
                                        └────────────────────┘
```

## Key use cases

| Use case | API | Event / effect |
|---|---|---|
| Open a dispute on a transaction | `POST /api/v1/disputes` | timeline `OPENED`, dispute event |
| Update status / resolution | `PUT /api/v1/disputes/{id}` | timeline `STATUS_CHANGED` |
| Add evidence | `POST /api/v1/disputes/{id}/evidence` | timeline `EVIDENCE_ADDED` |
| Withdraw a dispute | `POST /api/v1/disputes/{id}/withdraw?actor=…` | status `WITHDRAWN`, resolution `WITHDRAWN` |
| Escalate a dispute | `POST /api/v1/disputes/{id}/escalate?actor=…` | status `ESCALATED` |
| Get dispute / by reference | `GET /api/v1/disputes/{id}`, `…/reference/{ref}` | — |
| List by account / by status | `GET /api/v1/disputes/account/{accountId}`, `?status=` | — |
| Read timeline / evidence | `GET …/{id}/timeline`, `…/{id}/evidence` | — |

## Callers

- **admin-ui** (via Keycloak token) — operators and compliance staff managing disputes
- **customer app / API** — opening a dispute and uploading evidence references on behalf of the cardholder
- **service callers** (`ROLE_API`) — automated flows that open or update disputes

## Dependencies

- **PostgreSQL** (database `openbank_dispute`)
- **Kafka** (topic `openbank.disputes.dispute.event`)
- **Redis (Valkey)** — client configured (intended for idempotency); enforcement TBD
- **Keycloak** — auth (realm `openbank`, client `openbank-services`)
- **OPA sidecar** — advisory authorization (ADR-0034), `authz.enforce=false` by default
- **openbank-libs** — `authz.@Authorize`, outbox plumbing, BuildInfo, DocsResource

## Business value

- **Single source of truth** for the lifecycle of a payment dispute — one reference, one status machine, one timeline.
- **Regulatory deadline tracking** — `resolutionDeadline` makes the consumer-protection SLA explicit and queryable.
- **Audit-ready** — every state change appends an immutable timeline event and emits a domain event for `audit-service`.
- **Operator efficiency** — admin UI can list disputes by status/account, attach evidence and escalate from a single surface.
