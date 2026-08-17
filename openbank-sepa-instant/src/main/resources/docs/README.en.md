# openbank-sepa-instant — Documentation

> **What it is:** the execution engine for **SEPA Instant Credit Transfer (SCT Inst)** — sub-10s settlement with a synchronous sanctions screening gate and recall support. **What it is NOT:** the double-entry ledger (`openbank-ledger-service`), the transaction store (`openbank-transaction-service`), the balance engine (`openbank-balance-service`), nor the regular (non-instant) SEPA rail (`openbank-sepa-payment-service`).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, screening gate, direct event-publish flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, sanctions mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive (Panache) + Reactive PostgreSQL / SmallRye Reactive Messaging (Kafka) — built via the `openbank.quarkus-service` convention plugin
- **Port:** 8127 (app), 8085 (management — `/q`)
- **Persistence:** dedicated database `openbank_sepa_instant`, schema declared `sepa_instant_schema`, Flyway migrations V1..V4
- **Events:** direct, synchronous publish from `SctInstPaymentService` at each lifecycle transition via `KafkaSctInstEventPublisher` → Kafka topic `openbank.sepa.instant.events` (not a transactional outbox — an earlier outbox pipeline was built but never wired to a real call site and was removed, issue #1034)
- **Idempotency:** `Idempotency-Key` header (falls back to body field) → unique constraint on `sct_inst_payments.idempotency_key`
- **Auth:** Keycloak OIDC (client `openbank-services`); OPA authz (ADR-0034) advisory by default; `@Authorize` on recall
- **Money-path:** YES — listed in `rules.yaml: money_path_services`; ADR-0057 tier **T0 (always-on)**; threat model at `docs/threat-models/openbank-sepa-instant.md`
- **Screening gate:** synchronous sanctions screening of debtor + creditor names on submit (ADR-0032), fail-closed
