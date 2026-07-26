# openbank-party-service — Documentation

> **What it is:** the system of record for **parties** — the natural persons and legal entities (customers, sole traders, companies, trusts) the bank holds a relationship with, including their identity documents and KYC/AML lifecycle state. **What it is NOT:** the account register (`openbank-account-service`), the KYC case engine (`openbank-kyc-service`), the AML screening engine (`openbank-aml-service`), nor the encrypted birth-number vault (`openbank-pid-service`).

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

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 21+ / PostgreSQL 16 / Hibernate Reactive (Panache) + reactive PostgreSQL client
- **Port:** 8111 (app/HTTP), 8085 (management — health, metrics, docs)
- **Persistence:** dedicated PostgreSQL database `openbank_parties`, Flyway migrations V1..V8
- **Outbox:** `party_outbox` → Kafka topic `openbank.party.events` (dispatcher polls every 5 s with fault-tolerance: circuit breaker + retry + bulkhead + timeout)
- **Inbound events:** consumes `openbank.kyc.events` and `openbank.aml.events` to drive the two-key KYC+AML activation gate
- **Idempotency:** `Idempotency-Key` header is required on `POST /api/v1/parties`; the email uniqueness constraint additionally de-duplicates parties (409 on replay)
- **Auth:** Keycloak OIDC, roles `ROLE_VIEWER` / `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_KYC` / `ROLE_API`; OPA authz in advisory mode (ADR-0034)
- **Data classification:** `restricted` — this service holds PII (legal name, e-mail, phone, address, nationality, tax id). Not a money-path service.
