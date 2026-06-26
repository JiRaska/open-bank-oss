# openbank-swift-service — Documentation

> **What it is:** the service that records, validates, tracks and dispatches **SWIFT MT messages** (MT103, MT202, MT900/910/940/950, MT199) for cross-border, high-value wire instructions. **What it is NOT:** it is not the payment-initiation engine (the SEPA/domestic payment services own that), not the ledger (`openbank-ledger-service`), not the sanctions/AML screening engine (`openbank-sanctions-service` / `openbank-aml-service`), and it does not maintain account balances.

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

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) — reactive PG client, SmallRye Kafka, SmallRye Fault Tolerance, OpenTelemetry, Micrometer/Prometheus
- **Port:** 8122 (app), 8085 (management, root-path `/q`)
- **Persistence:** dedicated database `openbank_swift` (declared logical schema `swift_schema`), Flyway migrations V1..V3
- **Outbox:** `swift_outbox` table → Kafka channel `swift-events-out` → topic `openbank.payments.swift.event`
- **Idempotency:** client-supplied `idempotencyKey` field on the send command, deduplicated by a `UNIQUE` constraint in `swift_messages` (a Redis client is also wired)
- **Auth:** Keycloak OIDC (client `openbank-services`); OPA sidecar authorization (ADR-0034) in advisory mode by default (`authz.enforce=false`), `@Authorize` on the acknowledge action
- **Classification:** **money-path** service (`rules.yaml: money_path_services`) — 2 approvals + threat model required ([threat model](../../../../docs/threat-models/openbank-swift-service.md))
