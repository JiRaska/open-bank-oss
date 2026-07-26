# openbank-dispute-service — Documentation

> **What it is:** the system of record for **payment disputes and chargebacks** — it opens a dispute against a transaction, collects evidence, tracks a status/resolution lifecycle and keeps an append-only timeline. **What it is NOT:** it does NOT reverse money or post entries (that's `ledger-service` / `transaction-service`), it does NOT clear card scheme chargebacks with an external network, and it does NOT decide fraud (that's the fraud/AML pipeline) — it records the workflow and emits events.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 21+ / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Port:** 8135 (app), 8085 (management — health, metrics, docs, root-path `/q`)
- **Persistence:** PostgreSQL database `openbank_dispute`, Flyway migrations V1..V4
- **Outbox:** `dispute_outbox` table → Kafka topic `openbank.disputes.dispute.event` (channel `dispute-events-out`)
- **Idempotency:** `Idempotency-Key` accepted by CORS; a Redis client is available but enforcement is **not yet wired** (TBD) — open uses a generated `DSP-<epochMillis>` reference
- **Auth:** Keycloak OIDC (realm `openbank`, client `openbank-services`); roles `ROLE_VIEWER` (read), `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API` (mutations); OPA/`@Authorize` advisory (ADR-0034)
- **Money-path:** **No** — not listed in `rules.yaml: money_path_services` (1 approval, no threat model required)
