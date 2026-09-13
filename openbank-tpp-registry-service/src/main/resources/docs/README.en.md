# openbank-tpp-registry-service — Documentation

> **What it is:** the registry of Third Party Providers (TPPs) authorised under PSD2 — licensed AISP/PISP/PIISP institutions, their eIDAS certificates, registration status and blacklist state. It answers the question "is this TPP allowed to do AIS/PIS/PIIS right now?". **What it is NOT:** the consent store (that's `openbank-consent-service`), the strong-customer-authentication engine (`openbank-sca-service`), nor the Open Banking façade itself (`openbank-psd2-service`).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8108 (app), 8085 (management — `/q`)
- **Persistence:** PostgreSQL database `openbank_tpp_registry`, Flyway migrations V1/V3/V4
- **Outbox:** `tpp_outbox` → Kafka topic `openbank.tpp.registry.event` (channel `tpp-events-out`); `TPP_REGISTERED` / `TPP_BLACKLISTED` are written in the state-change transaction (issue #4007, see [02](./02-architecture.md))
- **Idempotency:** `Idempotency-Key` header → Redis (via `openbank-libs` `IdempotencyStore`)
- **Auth:** Keycloak OIDC, OPA sidecar authorization (ADR-0034) in advisory mode (`AUTHZ_ENFORCE=false` by default); `@Authorize` on the blacklist mutation
- **Capability:** Open Banking (PSD2) — **not** a money-path service
