# openbank-lending-service — Documentation

> **What it is:** the lending / credit bounded context — loan origination (four-eyes maker-checker), servicing (repayment schedules, accrual-basis interest), collateral registration and IFRS 9 provisioning (ADR-0028). **What it is NOT:** the double-entry book (that's `openbank-ledger-service`); the loan book never owns balances — it posts cash events to the ledger.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, four-eyes flow, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, AML, IFRS 9, EBA mapping |
| [07 — Risk-Model Calibration](./07-risk-model-calibration.md) | Credit risk, model risk, audit | PD/LGD provenance, versioning, calibration replay, honest limits |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8126 (app), 8086 (management, root-path `/q`)
- **Persistence:** dedicated database `openbank_lending` (governance schema name `lending_schema`), Flyway migrations V1..V3
- **Outbox:** `lending_outbox` → Kafka topic `openbank.lending.events`
- **Ledger posting:** never mutates balances — posts balanced double-entry journals to `ledger-service` (`POST /api/v1/journals`), build-time gated by `lending.ledger.backend=rest`
- **Auth:** Keycloak OIDC; roles `ROLE_LENDING_OFFICER`, `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN`
- **Money-path:** yes — 2 approvals + threat model required (`docs/threat-models/openbank-lending-service.md`)
