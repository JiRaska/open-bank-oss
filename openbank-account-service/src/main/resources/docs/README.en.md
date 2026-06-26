# openbank-account-service — Documentation

> **What it is:** a core banking service holding the definition and state of customer accounts (current, savings, technical). **What it is NOT:** the balance engine (that's `openbank-balance-service`), nor the transactional ledger (`openbank-ledger-service`).

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

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8100 (app), N/A (no separate mgmt port yet)
- **Persistence:** dedicated DB schema `account`, Flyway migrations V1..V6
- **Outbox:** `account_outbox` → Kafka topic `openbank.account.events.v1`
- **Idempotency:** `Idempotency-Key` header → Redis (24 h TTL)
- **Auth:** Keycloak OIDC, role `ROLE_OPERATOR` required for mutations
