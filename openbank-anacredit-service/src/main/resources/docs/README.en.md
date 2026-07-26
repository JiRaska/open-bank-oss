# openbank-anacredit-service — Documentation

> **What it is:** a derive-only regulatory reporting service that renders the **AnaCredit credit dataset** (Reg. (EU) 2016/867, collected by ČNB) for a monthly reference date from registered credit exposures — overdrafts first ([ADR 0037](../../../../docs/adr/0037-anacredit-credit-exposure-reporting.md)). **What it is NOT:** a money-mover (it posts nothing, emits no events), not the source of truth for credit instruments (that is `lending-service` / `balance-service`), and not the ČNB submission transport (no SDMX channel in v1).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, regulatory | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, the render pipeline |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Storage model, eligibility rules, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, FinOps tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | AnaCredit, DORA, GDPR mapping, data flows |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / RESTEasy Reactive + Jackson + reactive Panache/Postgres
- **Port:** 8137 (app + management on the same port)
- **Persistence:** **PostgreSQL** (`openbank_anacredit`, governance schema label `anacredit_schema`, ADR-0037 v2 — the `openbank-product-catalog` pattern). `credit_exposures` table, Flyway-migrated, reactive-Panache-backed.
- **Outbox / events:** **none** — derive-only, the service emits no domain events and consumes none.
- **Idempotency:** none — exposure registration is an `upsert` keyed by `instrumentId` (naturally idempotent); reads are pure.
- **Auth:** Keycloak OIDC, roles `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_COMPLIANCE`, `ROLE_API`.
- **Money-path:** **No** — off the ADR-0030 gate (no threat model / 2-approval requirement).
