# Overview

## What the service does

`openbank-sepa-instant` executes **SEPA Instant Credit Transfers (SCT Inst)** — the instant rail with sub-10s settlement targets. It holds:

- **SctInstPayment aggregate** — the payment instruction and its lifecycle: identifiers (`paymentId`, `endToEndId`, `idempotencyKey`), debtor (account id, IBAN, name), creditor (IBAN, name, optional BIC), `amount` + `currency` (default EUR), `remittanceInfo`, the `executionTimeoutAt` watchdog, and outcome timestamps/reasons (`settledAt`, `recalledAt`, `recallReason`, `rejectReason`, `rejectDetail`).
- **Status state machine** — `PENDING → PROCESSING → SETTLED` on the happy path, with `REJECTED`, `TIMEOUT`, and `RECALLED` as terminal/branch states.
- **A synchronous sanctions screening gate** (ADR-0032) on submit: both debtor and creditor names are screened **before** the payment is released. The screening decision (CLEAR / REVIEW / BLOCK) is made by the pure `ScreeningPolicy` in the domain.

## What the service **does NOT** do

- ❌ Does not keep the double-entry book — that is `ledger-service`.
- ❌ Does not store the canonical transaction — it asks `transaction-service` to create one (lineage `creates`).
- ❌ Does not compute balances — that is `balance-service`.
- ❌ Does not run the regular, non-instant SEPA rail — that is `sepa-payment-service`.
- ❌ Does not own the sanctions lists or AML case investigation — it **calls** `sanctions-service` (screen) and `aml-service` (open case); it never decides AML adjudication itself.
- ❌ Does not maintain account definitions — that is `account-service`.

## Position in the domain

```
   ┌────────────┐  POST /api/v1/sepa-instant   ┌──────────────────────┐
   │  caller    │ ───────────────────────────► │ openbank-sepa-instant│
   │ (admin UI/ │                              │                      │
   │  customer) │                              │  ScreeningPolicy gate│
   └────────────┘                              └───────┬──────────────┘
                                                       │ sync screen (debtor+creditor)
                          ┌────────────────────────────┼────────────────────────────┐
                          ▼                            ▼                             ▼
                 ┌────────────────┐          ┌──────────────────┐         outbox → Kafka
                 │ sanctions-svc  │          │   aml-service    │      openbank.sepa.instant.events
                 │  POST /screen  │          │ POST /aml/cases  │                 │
                 └────────────────┘          └──────────────────┘                 ▼
                                                                       ┌──────────────────────┐
   PostgreSQL                                                          │ transaction-service  │
   (openbank_sepa_instant)                                            │ ledger / balance     │
                                                                       │ audit / notification │
                                                                       └──────────────────────┘
```

## Key use cases

| Use case | API | Event / downstream call |
|---|---|---|
| Submit an instant payment (screened) | `POST /api/v1/sepa-instant` | `SctInstPaymentSubmitted` (on CLEAR) |
| Sanctions hit → block | (same submit) | `SctInstPaymentRejected` + CRITICAL AML case |
| Potential hit / screening outage → hold | (same submit) | held `PENDING`, HIGH/MEDIUM AML case opened |
| Get a payment by id | `GET /api/v1/sepa-instant/{paymentId}` | — |
| List payments for a debtor account | `GET /api/v1/sepa-instant/debtor/{debtorAccountId}` | — |
| List all payments | `GET /api/v1/sepa-instant` | — |
| Recall a settled payment | `POST /api/v1/sepa-instant/{paymentId}/recall` | `SctInstPaymentRecalled` |
| Execution watchdog timeout | (internal) | `SctInstPaymentTimeout` |

## Callers

- **admin-ui / customer-facing payment flows** (via Keycloak token) — submit, query, recall.
- Other OpenBank services consuming the emitted events (transaction, ledger, balance, audit, notification) over Kafka.

## Dependencies

- **PostgreSQL** (`openbank_sepa_instant`, schema `sepa_instant_schema`)
- **Kafka** (`openbank-kafka`, topic `openbank.sepa.instant.events`)
- **sanctions-service** — synchronous screen (`POST /api/v1/sanctions/screen`); fail-closed if unreachable
- **aml-service** — open AML case (`POST /api/v1/aml/cases`); best-effort follow-up
- **Redis (Valkey)** — client configured (`redis://…`)
- **Keycloak** — OIDC auth; **OPA** sidecar — authz (ADR-0034, advisory by default)
- **openbank-libs** — authz (`@Authorize`), outbox/event plumbing, BuildInfo, DocsResource

## Business value

- **Instant settlement with compliance assurance** — every instant payment is sanctions-screened synchronously before it leaves the bank; no payment ever settles un-screened (fail-closed).
- **Auditable outcomes** — submit / reject / settle / timeout / recall each emit a domain event for the audit trail.
- **Recall workflow** — supports fraud / duplicate / wrong-amount / wrong-beneficiary recall of settled instant payments.
- **Money-path resilience** — always-on (T0) with circuit-breaker, retry and timeout fault tolerance on the screening hop.
