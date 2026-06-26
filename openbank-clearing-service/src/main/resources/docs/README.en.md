# openbank-clearing-service — Documentation

> **What it is:** the payment **clearing & settlement** service — it groups individual payments into batches per payment rail, runs settlement cycles, computes net positions per participant, and tracks the lifecycle of each clearing item (PENDING → IN_CLEARING → SETTLED). **What it is NOT:** it does not originate payments (`sepa-payment` / `domestic-payment` / `sepa-instant` / `swift` do), it does not keep balances (`balance-service`), and it does not post double-entry bookings (`ledger-service`).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, roles, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK / PostgreSQL 16 / Hibernate Reactive (Panache) + Reactive PG client
- **Port:** 8124 (app HTTP), 8085 (management — health/metrics, root-path `/q`)
- **Persistence:** dedicated PostgreSQL database `openbank_clearing`, Flyway migrations V1..V4
- **Outbox:** `clearing_outbox` → Kafka topic `openbank.clearing.batch.event` (channel `clearing-events-out`); dispatcher polls every 5 s
- **Idempotency:** `Idempotency-Key` accepted as a CORS/allowed header; Redis (Valkey) is wired as a dependency
- **Auth:** Keycloak OIDC; per-operation `@RolesAllowed` (least-privilege); `settle` also `@Authorize` via OPA (advisory)
- **Money-path:** **YES** — this service is in `rules.yaml: money_path_services` (2 approvals + threat model required)
