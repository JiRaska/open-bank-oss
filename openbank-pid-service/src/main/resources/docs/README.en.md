# openbank-pid-service — Documentation

> **What it is:** the unified identity service (Party Identity Data) — the system of record for *who a party is* (natural person, legal entity, sole trader): core identity attributes, external identifiers (bankID, ROB/AIFO, IČO, Keycloak), KYC/AML attributes, addresses, contact details, and the relationships (roles) a party has with the bank. **What it is NOT:** it does NOT open or hold accounts (`openbank-account-service`), does NOT run the KYC/AML decisioning workflow itself (`kyc-service` / `aml-service` / `sanctions-service`), and does NOT issue authentication tokens (Keycloak does that).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, eIDAS mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus / JDK 25 / PostgreSQL / Hibernate Reactive (Panache) + reactive PG client
- **Port:** 8105 (app), 8085 (management — health, metrics, OpenAPI, docs)
- **Persistence:** dedicated database `openbank_pid`, Flyway migrations V1..V4
- **Outbox:** table `pid_outbox`, dispatched every 5 s with fault-tolerance (circuit breaker / retry / timeout); domain events are also published directly to Kafka topic `party.events`
- **Idempotency:** no `Idempotency-Key` cache today (creation deduplicated on the unique bankID `sub`); see [03 — API](./03-api.md)
- **Auth:** Keycloak OIDC (RS256 JWT), roles `openbank-employee` / `openbank-admin` / `openbank-customer`; OPA `@Authorize` in advisory mode (ADR-0034)
- **Money-path:** NO — pid-service is not in `rules.yaml: money_path_services`; it is an identity / reference-data service (`dataClassification: restricted`)
