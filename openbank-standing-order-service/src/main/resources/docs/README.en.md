# openbank-standing-order-service — Documentation

> **What it is:** a payments service that holds the definition and lifecycle of **recurring payment orders** (standing orders) — create, pause, resume, cancel — and emits domain events that drive downstream payment execution. **What it is NOT:** it does NOT move money itself (the actual payment is created in `transaction-service` / the SEPA/domestic payment services), it is NOT the ledger, and it is NOT the balance engine.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 21+ / PostgreSQL 16 / Hibernate Reactive (Panache) — versions resolved from the shared `libs.versions.toml` (see [05 — Operations](./05-operations.md)).
- **Port:** 8121 (app), 8085 (management, root-path `/q`)
- **Persistence:** dedicated database `openbank_standing_orders`, Flyway migrations V1..V3
- **Outbox:** `standing_order_outbox` → Kafka topic `openbank.standing-orders.order.event`
- **Idempotency:** `idempotencyKey` (per-request, unique constraint in DB) → create is replay-safe
- **Auth:** Keycloak OIDC; authorization via OPA sidecar (`@Authorize`, ADR-0034) in advisory mode by default
- **Money-path:** **No** — not listed in `rules.yaml: money_path_services` (single-approval, no threat model required)
