# openbank-transaction-service — Documentation

> **What it is:** the money-path service that **initiates and records transactions** — it orchestrates a payment saga (reserve funds → post to the ledger → capture → complete) and is the system of record for transaction history and BIAN-aligned transaction search. **What it is NOT:** it is not the double-entry book (that's `openbank-ledger-service`), not the balance engine (`openbank-balance-service`), and not a payment-scheme adapter (`sepa-payment`, `domestic-payment`, `swift-service`, … create transactions here).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, payment saga, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive (Panache) / Reactive PostgreSQL / SmallRye Kafka — see `build.gradle.kts`
- **Port:** 8102 (app), 8085 (management, `/q`)
- **Persistence:** PostgreSQL database `openbank_transactions`, range-partitioned `transactions` table, Flyway migrations V1..V7
- **Outbox:** `transaction_outbox` → Kafka topic `openbank.transactions.transaction.initiated` (also `.completed` / `.failed` event types)
- **Idempotency:** caller-supplied `idempotencyKey` on the initiate command → unique constraint on `(idempotency_key, booking_date)` and on the payment saga
- **Orchestration:** synchronous payment saga (hold → ledger post → debit/credit → complete, with compensation)
- **Auth:** Keycloak OIDC; reads require `ROLE_API`/`ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`, initiation requires `ROLE_OPERATOR`
- **Money-path service** (see `rules.yaml: money_path_services`) — 2 approvals + threat model on every change
