# openbank-consent-service — Documentation

> **What it is:** the system of record for **data-sharing and payment-initiation consents** (PSD2 AIS/PIS/CBPII grants, plus AI-agent delegation scopes) granted by a customer to a third party or agent. **What it is NOT:** the SCA engine (that's `openbank-sca-service`), the PSD2 protocol gateway (`psd2-service`), nor the account/balance data it protects (`openbank-account-service`, `openbank-balance-service`).

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
- **Port:** 8106 (app), 8085 (management — health, metrics, docs)
- **Persistence:** dedicated database `openbank_consents` (tables in `public`), Flyway migrations V1..V4
- **Outbox:** `consent_outbox` → Kafka topic `openbank.consent.events`
- **Idempotency:** derived key from `tppTransactionId` (falls back to `X-Request-ID`) → Redis (24 h TTL) on consent creation
- **Auth:** Keycloak OIDC (RS256 JWT); OPA sidecar authorization (ADR 0034, advisory by default); `@Authorize` on revoke
- **SCA:** activation requires a `COMPLETED` SCA challenge from `openbank-sca-service` (ADR 0021)
- **Money-path service:** yes — 2 approvals + threat model required on every change
