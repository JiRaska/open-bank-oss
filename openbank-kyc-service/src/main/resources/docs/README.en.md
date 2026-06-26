# openbank-kyc-service — Documentation

> **What it is:** the case-management service for Know Your Customer / Customer Due Diligence — it opens KYC cases, tracks individual compliance checks (identity, address, PEP, sanctions, adverse media), and records the four-eyes approve/reject decision for a party. **What it is NOT:** the sanctions/PEP screening engine itself (that's `openbank-sanctions-service` / `openbank-aml-service`), nor the customer master data (that's `openbank-party-service`).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | AMLD, GDPR, EBA, DORA mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) — see `build.gradle.kts`
- **Port:** 8114 (app), 8085 (management, root-path `/q`)
- **Persistence:** PostgreSQL database `openbank_kyc`, Flyway migrations V1..V5
- **Outbox:** `kyc_outbox` → Kafka topic `openbank.kyc.events`
- **Inbound events:** consumes `openbank.party.events` (`PARTY_CREATED` → auto-open a case, ADR-0068)
- **Idempotency:** at the domain level — at most one active case per party (partial unique index `uq_kyc_cases_active_party`, V5); no per-request idempotency cache
- **Auth:** Keycloak OIDC; mutations require `ROLE_KYC`/`ROLE_ADMIN`; OPA advisory authz (ADR-0034)
