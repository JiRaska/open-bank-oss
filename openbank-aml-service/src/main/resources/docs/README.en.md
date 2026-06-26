# openbank-aml-service — Documentation

> **What it is:** the Anti-Money Laundering (AML) **case-management** service — it opens, reviews and decides AML screening cases for parties, accounts and transactions. **What it is NOT:** the sanctions/PEP list-matching engine (that's `openbank-sanctions-service`), nor the KYC identity-verification service (`openbank-kyc-service`), nor a payment surface — it does not move money or block payments directly.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | AMLD, DORA, GDPR, PSD2, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK (build 20, runtime 25 Temurin) / PostgreSQL 16 / Hibernate Reactive (Panache) — reactive PG client
- **Port:** 8117 (app), 8085 (management `/q`)
- **Persistence:** dedicated database `openbank_aml`, Flyway migrations V1..V5 (declared data domain `compliance`, logical schema name `aml_schema`)
- **Outbox:** `aml_outbox` → Kafka topic `openbank.aml.events` (ADR-0050 regulatory-grade dispatch)
- **Inbound events:** consumes `openbank.party.events` to open onboarding screening cases on `PARTY_CREATED`
- **Idempotency:** `Idempotency-Key` header → Redis (Valkey); also a unique `idempotency_key` per case row
- **Auth:** Keycloak OIDC; mutations require `ROLE_OPERATOR`, `ROLE_ADMIN` or `ROLE_COMPLIANCE`; OPA sidecar authz (ADR-0034, advisory by default)
- **Money-path:** **No** — not listed in `rules.yaml: money_path_services` (1 approval, no mandatory threat model). It is a compliance-screening service in the FinOps `compliance-screening` group.
