# Overview

## What the service does

`openbank-aml-service` is the **system of record for AML screening cases** in the OpenBank platform. It manages the lifecycle of an AML case from creation through analyst review to a final decision:

- **AmlCase aggregate** — id, owning `partyId`, optional `accountId` / `transactionId`, `customerReference`, `screeningType` (CUSTOMER_ONBOARDING / TRANSACTION_MONITORING / PERIODIC_REVIEW / MANUAL_INVESTIGATION), `riskLevel` (LOW / MEDIUM / HIGH / CRITICAL), `status`, `alertCode`, matched-entity details and the decision metadata (`decisionReason`, `assignedAnalyst`, `decidedBy`, `decidedAt`).
- **Case state machine** — `OPEN` / `UNDER_REVIEW` / `ESCALATED` → terminal `CLEARED` or `BLOCKED`. HIGH/CRITICAL risk cases open directly `UNDER_REVIEW`; LOW/MEDIUM open `OPEN`. Terminal states cannot transition further.
- **Compliance metadata** — matched-list tracking, fuzzy match score, false-positive flagging, MLRO escalation and SAR (Suspicious Activity Report) filing references (DB columns from V2).

## What the service **does NOT** do

- Does not match names against sanctions / PEP / adverse-media lists — that's `openbank-sanctions-service`.
- Does not perform KYC identity verification — that's `openbank-kyc-service`.
- Does not move money, post to the ledger, or directly reject payments — payment surfaces call out to screening and act on the result themselves.
- Does not freeze accounts — `openbank-account-service` owns the freeze/unfreeze workflow.
- Does not file the SAR with the FIU externally — it records the SAR reference; the actual filing is an out-of-band compliance-ops action.

## Position in the domain

```
   ┌──────────────┐  PARTY_CREATED (Kafka)   ┌───────────────┐
   │ party-service│ ───────────────────────► │  aml-service  │
   └──────────────┘                           │ (open case)   │
                                              └──────┬────────┘
   ┌──────────────┐  POST /aml/cases                │ outbox → Kafka
   │ payment svcs │ ───────────────────────►        ▼  openbank.aml.events
   │ sepa/instant │                           ┌────────────────┐
   │ domestic/swift│                          │ party-service  │ (AML key of
   └──────────────┘                           │ audit-service  │  activation gate)
   ┌──────────────┐  POST /aml/cases          └────────────────┘
   │ admin UI     │ ───────────────────────►        │
   │ (compliance) │  PUT .../decision               ▼
   └──────────────┘                              PostgreSQL
                                              (db: openbank_aml)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Open a screening case (manual / payment / transaction) | `POST /api/v1/aml/cases` | `aml.case.created.v1` |
| Open an onboarding screening case for a new party | consumes `PARTY_CREATED` on `openbank.party.events` | `aml.case.created.v1` |
| Get a case by id | `GET /api/v1/aml/cases/{caseId}` | — |
| List / filter cases | `GET /api/v1/aml/cases?status=&partyId=&screeningType=` | — |
| Record an analyst decision (clear / block / escalate) | `PUT /api/v1/aml/cases/{caseId}/decision` | `aml.case.status_changed.v1` |

## Callers

- **admin-ui** (via Keycloak token) — compliance analysts and MLRO open cases and record decisions through the compliance cockpit.
- **payment services** (sepa-payment, sepa-instant, domestic-payment, swift-service) — open a screening case as part of their payment-screening gate (declared upstream `api` lineage in `governance.yaml`).
- **party-service** (Kafka) — emits `PARTY_CREATED` to trigger onboarding screening; consumes `aml.case.status_changed.v1` for the AML key of its KYC+AML two-key activation gate.
- **kyc-service / sanctions-service** (Kafka, declared `topic` lineage) — trigger / feed screening signals.

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_aml`)
- **Kafka** (`openbank-kafka`, outgoing topic `openbank.aml.events`, incoming `openbank.party.events`)
- **Redis (Valkey)** — idempotency cache
- **Keycloak** — auth (OIDC)
- **OPA sidecar** — unified authorization (ADR-0034), advisory by default
- **openbank-libs** — `IdempotencyStore`, `Authorize`/PDP, `ApiError`/`ErrorCode`, outbox plumbing, `ServiceInfoResource`, `DocsResource`

## Business value

- **Single source of truth** for AML case state and decisions — every screening alert and analyst decision is recorded with full audit metadata (who decided, why, when).
- **Four-eyes decisioning** — in production the decision endpoint is the only path to a terminal `CLEARED`/`BLOCKED` state (sandbox auto-clear is feature-flagged off by default).
- **Regulatory evidence** — case lifecycle events flow via outbox + Kafka to `audit-service` for the statutory retention period; SAR/MLRO tracking columns support 6AMLD reporting obligations.
- **Onboarding gate participation** — provides the AML key for party activation, ensuring no customer is activated without a screening case.
