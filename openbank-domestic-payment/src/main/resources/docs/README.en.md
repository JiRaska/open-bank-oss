# openbank-domestic-payment — Documentation

> **What it is:** the initiation and lifecycle service for **Czech domestic payments** (CZK rails, bank-code + account-number addressing, variable/specific/constant symbols, SIPO). It screens every payment synchronously against the sanctions lists before releasing it (ADR-0032). **What it is NOT:** the clearing/settlement engine (that's `clearing-service`), the double-entry ledger (`ledger-service`), the SEPA/cross-border path (`sepa-payment`/`sepa-instant`/`swift-service`), nor the sanctions/AML decision authority (`sanctions-service`/`aml-service`).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

This is a **money-path service** (`rules.yaml: money_path_services`): changes need 2 approvals + a threat model ([`docs/threat-models/openbank-domestic-payment.md`](../../../../docs/threat-models/openbank-domestic-payment.md)).

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, screening gate, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, CNB mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 (build on JDK 20) / PostgreSQL 16 / Hibernate Reactive (Panache) + reactive PG client
- **Port:** 8116 (app), 8085 (management, root-path `/q`)
- **Persistence:** dedicated database `openbank_domestic_payments`, Flyway migrations V1..V5 (governance manifest declares logical schema name `domestic_schema`)
- **Outbox:** `domestic_payment_outbox` → Kafka topic `openbank.domestic.payment.events` (channel `events-out`)
- **Idempotency:** `Idempotency-Key` is durably bound to the normalized request + actor in PostgreSQL
- **Auth:** Keycloak OIDC; mutations require `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`; OPA authz sidecar (ADR-0034, advisory by default)
- **Screening:** synchronous sanctions screening of debtor + creditor names on create, fail-closed (ADR-0032)
