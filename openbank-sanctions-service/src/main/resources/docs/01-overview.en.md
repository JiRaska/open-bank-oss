# Overview

## What the service does

`openbank-sanctions-service` is the **compliance gate for sanctions screening** in the OpenBank platform. It:

- **Screens entities** — individuals, organisations, vessels and aircraft — against up to 6 international and domestic sanctions lists before payments are processed or accounts are opened.
- **Manages sanctions lists** — holds the configuration (source URL, refresh schedule, enabled flag) for each list and runs periodic background refreshes via configurable cron.
- **Records every screening result** as a `SanctionsCheck` with a fuzzy match score (0.0–1.0), matched list entries, and match type (EXACT / FUZZY / PHONETIC / ALIAS).
- **Supports the manual review workflow** — compliance officers can submit `ReviewCommand` decisions to move a `POTENTIAL_HIT` to `CLEAR`, `HIT`, `WHITELISTED`, or `ESCALATED`.
- **Publishes screening events** to Kafka via the transactional outbox, so downstream services (AML, audit, notification) can react without polling.

## What the service does NOT do

- Does not perform KYC identity verification — that is `openbank-kyc-service`.
- Does not run AML transaction monitoring — that is `openbank-aml-service`.
- Does not block payments directly — it emits events; the payment services gate on the event result.
- Does not store copies of the external list data — only match metadata and scores are persisted.
- Does not make autonomous freeze decisions — freeze is initiated by `account-service` after a human review confirms a `HIT`.

## Position in the domain

```
   ┌─────────────────┐  ScreenEntityCommand  ┌──────────────────────┐
   │  sepa-payment   │ ─────────────────────► │  sanctions-service   │
   │  domestic-pay   │                        │  (this service)      │
   │  account-svc    │                        └────────┬─────────────┘
   │  fx-service     │                                 │  outbox → Kafka
   └─────────────────┘                                 ▼
                                             openbank.sanctions.screening.event
                                                        │
                                          ┌─────────────┼─────────────┐
                                          ▼             ▼             ▼
                                     aml-service   audit-service  notification
```

When a payment service initiates a transfer, it calls `POST /api/v1/sanctions/screen`. The response contains the `SanctionsCheckStatus` (`CLEAR` / `HIT` / `POTENTIAL_HIT` / `WHITELISTED` / `ESCALATED`). A `CLEAR` or `WHITELISTED` result allows the payment to continue; `HIT` or `ESCALATED` blocks it.

## Key use cases

| Use case | API | Event |
|---|---|---|
| Screen a party before payment | `POST /api/v1/sanctions/screen` | `SanctionChecked` |
| Review a potential hit | `POST /api/v1/sanctions/review` | `SanctionReviewed` |
| List all confirmed hits | `GET /api/v1/sanctions/hits` | — |
| List hits awaiting review | `GET /api/v1/sanctions/pending` | — |
| Enable / configure a list | `PUT /api/v1/sanctions/lists/{id}` | — |
| Manually refresh a list | `POST /api/v1/sanctions/lists/{listType}/refresh` | — |
| Refresh all enabled lists | `POST /api/v1/sanctions/lists/refresh-all` | — |

## Callers

- **sepa-payment-service**, **domestic-payment-service**, **sepa-instant-service**, **fx-service** — screen the counterparty before executing a transfer (ADR-0032 screening gate)
- **account-service** — screen the account owner at account opening
- **admin-ui** — compliance operators review pending hits and manage list configuration
- **kyc-service** — screens the individual during onboarding

## Dependencies

- **PostgreSQL** (`openbank-postgres`, schema `openbank_sanctions`)
- **Kafka** (`openbank-kafka`, topic `openbank.sanctions.screening.event`)
- **Redis (Valkey)** — idempotency deduplication cache
- **Keycloak** — OIDC authentication
- **openbank-libs** ≥ 0.1.0 — IdempotencyStore, outbox base, BuildInfo, DocsResource

## Business value

- **Regulatory compliance** — mandatory pre-payment OFAC/EU/UN screening under EU Regulation 2580/2001, Council Regulation (EU) 269/2014, and US OFAC rules.
- **Single enforcement point** — all payment services delegate to one service; no duplicated list logic across the fleet.
- **Audit trail** — every screening is persisted and published to `audit-service` with tamper-evident event chaining.
- **Human-in-the-loop** — fuzzy/phonetic matches trigger a manual review queue rather than an automatic block, reducing false positives.
- **Configurable lists** — compliance team can disable lists, change source URLs, and adjust refresh schedules without a code change.
