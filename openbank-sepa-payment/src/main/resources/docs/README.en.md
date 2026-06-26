# openbank-sepa-payment — Documentation

> **What it is:** the lifecycle owner for **SEPA Credit Transfers (SCT)** — it accepts a transfer instruction, runs the synchronous sanctions-screening gate, and drives the payment through its status lifecycle, emitting events for downstream clearing/ledger. **What it is NOT:** the instant-payment rail (`openbank-sepa-instant`), the domestic rail (`openbank-domestic-payment`), the clearing engine (`openbank-clearing-service`), the double-entry book (`openbank-ledger-service`), or the balance authority (`openbank-balance-service`).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

This is a **money-path service** (`rules.yaml: money_path_services`) — changes require 2 approvals and an up-to-date threat model ([`docs/threat-models/openbank-sepa-payment.md`](../../../../docs/threat-models/openbank-sepa-payment.md), ADR-0030).

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, screening gate, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, SEPA Rulebook mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / Reactive PG client
- **Port:** 8115 (app), 8085 (management, root-path `/q`)
- **Persistence:** dedicated database `openbank_sepa_payments`, declared schema `sepa_schema`, Flyway migrations V1..V4
- **Outbox:** `sepa_payment_outbox` → Kafka topic `openbank.sepa.payment.events` (drained every 5 s)
- **Idempotency:** `Idempotency-Key` header (required on create) → Redis (`libs.idempotency.IdempotencyStore`)
- **Auth:** Keycloak OIDC; write roles `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`, read adds `ROLE_VIEWER`; OPA advisory→enforce (ADR-0034)
- **Screening gate:** synchronous sanctions screen on create, fail-closed (ADR-0032)
- **FinOps tier:** T0 — always-on (money-path synchronous hop, ADR-0057)
