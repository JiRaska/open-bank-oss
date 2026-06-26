# openbank-sanctions-service — Documentation

> **What it is:** the real-time sanctions screening service for the OpenBank platform. It screens parties (individuals, organisations, vessels, aircraft) against OFAC SDN, EU Consolidated, UN Consolidated, HM Treasury, FATF High-Risk and CNB Domestic lists before any payment or account operation proceeds. **What it is NOT:** an AML transaction-monitoring engine (`openbank-aml-service`), a KYC identity-verification service (`openbank-kyc-service`), nor a fraud-detection engine.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, AML/CFT, PSD2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate (JPA/Panache)
- **Port:** 8123
- **Persistence:** dedicated DB schema `openbank_sanctions`, Flyway migrations V1..V4
- **Outbox:** `sanctions_outbox` → Kafka topic `openbank.sanctions.screening.event`
- **Idempotency:** `idempotencyKey` field in request body → Redis (deduplication)
- **Auth:** Keycloak OIDC, role `ROLE_OPERATOR` required for mutations
- **Lists:** OFAC_SDN, EU_CONSOLIDATED, UN_CONSOLIDATED, HM_TREASURY, FATF_HIGH_RISK, CNB_DOMESTIC
