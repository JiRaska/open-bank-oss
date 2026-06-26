# Overview

## What the service does

`openbank-standing-order-service` is the **system of record for recurring payment orders** (standing orders) in the OpenBank platform. It holds:

- **StandingOrder aggregate** — the durable instruction: debtor account, creditor (IBAN, name, optional BIC), amount (stored in minor units), currency, `frequency` (DAILY / WEEKLY / BIWEEKLY / MONTHLY / QUARTERLY / ANNUALLY), `paymentType` (SEPA_CREDIT / DOMESTIC / INTERNAL), optional remittance info, `startDate` / optional `endDate`.
- **Lifecycle state** — `status` ∈ ACTIVE / PAUSED / CANCELLED / COMPLETED / FAILED, plus execution bookkeeping (`nextExecutionDate`, `lastExecutionDate`, `executionCount`, `failureCount`).
- **Outbox** — a transactional outbox row per state change, dispatched to Kafka for downstream consumers.

## What the service **does NOT** do

- ❌ Does not move money — it does not debit the account or post to the ledger. The actual payment is created downstream (`transaction-service`, then the SEPA/domestic payment services).
- ❌ Does not keep balances — that is `balance-service`.
- ❌ Does not perform AML/sanctions screening — the downstream payment surfaces enforce the screening gate (ADR-0032) when the recurring payment is materialized.
- ❌ Does not validate account ownership / sufficient funds at execution time — that is the responsibility of the consuming payment service.
- ❌ Does not run double-entry accounting — that is `ledger-service`.

> **Maturity note:** the recurring **execution scheduler** (the component that walks `next_execution_date` and triggers a payment for each due order) is defined at the port level (`listDueForExecution`) but is not yet wired as a scheduled job in this build — only the outbox dispatcher runs on a timer. Treat scheduled materialization as a last-mile gap (TBD) rather than a shipped capability.

## Position in the domain

```
   ┌────────────┐  POST /standing-orders   ┌────────────────────────┐
   │  admin UI  │ ───────────────────────► │ standing-order-service │
   │ / customer │                          └───────────┬────────────┘
   └────────────┘                                      │ outbox → Kafka
                                                        ▼
                            ┌──────────────────────────────────────────┐
                            │ Kafka: openbank.standing-orders.order.event│
                            └───────────────┬──────────────────────────┘
                                            ▼
                            ┌────────────────────┐   ┌───────────────┐
                            │ transaction-service│   │ audit-service │
                            │ (creates payment)  │   │ notification  │
                            └────────────────────┘   └───────────────┘
                                            │
                                            ▼
                                       PostgreSQL
                              (DB: openbank_standing_orders)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Create a standing order | `POST /api/v1/standing-orders` | `StandingOrderCreated` |
| Get a standing order | `GET /api/v1/standing-orders/{id}` | — |
| List orders for a party | `GET /api/v1/standing-orders/party/{partyId}` | — |
| List all orders | `GET /api/v1/standing-orders` | — |
| Pause an active order | `POST /api/v1/standing-orders/{id}/pause` | — |
| Resume a paused order | `POST /api/v1/standing-orders/{id}/resume` | — |
| Cancel an order | `DELETE /api/v1/standing-orders/{id}` | `StandingOrderCancelled` |
| (planned) Execute due order | scheduler → downstream payment | `StandingOrderExecuted` |

## Callers

- **admin-ui / customer app** (via Keycloak token) — create, list, pause/resume, cancel standing orders.
- **transaction-service** — downstream consumer of the order events; it materializes the actual payment (governance lineage `downstream → transaction-service`).
- **audit-service** — consumes events for the audit trail.

## Dependencies

- **PostgreSQL** (database `openbank_standing_orders`)
- **Kafka** (topic `openbank.standing-orders.order.event`)
- **Redis (Valkey)** — client present (idempotency / caching plumbing)
- **Keycloak** — OIDC authentication
- **OPA sidecar** — authorization decisions (`@Authorize`, ADR-0034), advisory mode by default
- **openbank-libs** — authz API (`@Authorize`, `PolicyDecisionPoint`), outbox plumbing, `ServiceInfoResource` (`/api/v1/info`), `DocsResource`

## Business value

- **Single source of truth** for a customer's recurring instructions — the order definition lives in one place, decoupled from the act of paying.
- **Decoupled execution** via outbox + Kafka — downstream payment creation is eventually consistent and resilient (the dispatcher carries a circuit breaker, retry, bulkhead and timeout).
- **Auditable lifecycle** — every create/cancel emits a domain event for the audit pipeline.
- **Idempotent creation** — a client-supplied `idempotencyKey` (unique in the DB) makes order creation replay-safe.
