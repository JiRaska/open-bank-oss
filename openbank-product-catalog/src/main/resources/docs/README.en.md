# openbank-product-catalog — Documentation

> **What it is:** the system of record for **bank products and pricing** — the product master (savings, current, loan, mortgage, credit-card, term-deposit, overdraft, investment) and the bank-wide fee schedule. **What it is NOT:** it does not open accounts (`openbank-account-service`), does not move money or compute interest (`ledger`/`interest`/`balance`), and is **not** a money-path service.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, current persistence model |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Product model, fee schedule, persistence status, retention |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, FinOps tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, consumer-credit, MiFID II, transparency mapping |

## TL;DR

- **Tech stack:** Kotlin 2.3 / Quarkus 3.33 LTS / JDK 25 / RESTEasy Reactive + Jackson / SmallRye OpenAPI + Health
- **Port:** 8104 (application), 8085 (management health/metrics)
- **Persistence:** PostgreSQL via reactive Panache; Flyway owns the schema and the 15 banking examples are seeded only into an empty database (see [04 — Data](./04-data.md)).
- **Outbox / events:** v2 commits an audit row and transport-neutral outbox event atomically; no Kafka dispatcher ships yet.
- **Concurrency:** v1 optionally accepts `If-Match` and reports legacy 409; v2 requires it (missing 428, stale 412).
- **Auth:** OIDC resource server; reads require authentication and writes require OPERATOR/ADMIN. OPA remains advisory in the bank profile. See [03 — API](./03-api.md) and [06 — Compliance](./06-compliance.md).
- **Money-path:** **No** (not listed in `rules.yaml: money_path_services`).
- **API contract:** OpenAPI 3.1, `info.version` 2.0.0; compatible `/api/v1` and generic `/api/v2` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
