# openbank-onboarding-service — Documentation

> **What it is:** a read-only **projection** of the customer onboarding funnel (ADR-0068). It aggregates events from `party-service`, `kyc-service` and `sca-service` into a denormalised read-model for operator dashboards and the cockpit board. **What it is NOT:** a system of record — it never owns party, KYC or SCA state, never decides KYC approval, and (in this version) does not yet host the four-eyes approval queue.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, event-projection flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8130 (app), 8085 (management, root-path `/q`)
- **Persistence:** PostgreSQL database `openbank_onboarding`, single table `onboarding_records`, Flyway migrations V1..V2
- **Ingest:** consumes 3 Kafka topics — `openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events`
- **Outbox:** none — this service is a read-model and **does not publish** domain events
- **Idempotency:** not applicable — all REST endpoints are read-only GETs; event projection is upsert-by-`party_id` (naturally idempotent)
- **Auth:** Keycloak OIDC (realm `openbank`, client `openbank-services`). Per-role authorization (`@Authorize`/OPA enforce) is the ADR-0068 target and is **not yet wired** in the current REST layer (see [03 — API](./03-api.md))
