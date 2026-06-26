# openbank-fx-service — Documentation

> **What it is:** the foreign-exchange service holding FX rates (internal + ČNB central-bank fixing) and executing currency conversions with a synchronous sanctions screening gate (ADR-0032). **What it is NOT:** the balance engine (`openbank-balance-service`), the double-entry book (`openbank-ledger-service`), nor a payment initiator — it does not move money on accounts, it computes and records a conversion.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow, screening gate |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML/sanctions mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Port:** 8119 (app), 8085 (management, root-path `/q`)
- **Persistence:** PostgreSQL database `openbank_fx` (logical schema `fx_schema`), Flyway migrations V1..V3
- **Outbox:** `fx_outbox` → Kafka topic `openbank.fx.conversion.completed`
- **Idempotency:** `Idempotency-Key` header (mandatory on `POST /convert`) → de-duplicated on the `fx_conversions.idempotency_key` unique constraint
- **Auth:** Keycloak OIDC; roles `ROLE_VIEWER` / `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS` (convert requires OPERATOR/ADMIN/PAYMENTS)
- **Money-path:** yes (`rules.yaml: money_path_services`) — 2 approvals + threat model required
