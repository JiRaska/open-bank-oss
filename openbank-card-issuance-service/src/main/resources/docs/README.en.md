# openbank-card-issuance-service — Documentation

> **What it is:** the system of record for the **card lifecycle** — it issues debit/credit/prepaid/virtual cards against an existing account and party, and drives their state (PENDING → ACTIVE → SUSPENDED / BLOCKED / EXPIRED / CANCELLED). **What it is NOT:** a card authorization/transaction processor (it does not approve POS/ATM payments), nor a PAN vault (it stores a masked PAN only — no full card number, CVV, or PIN).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, PCI DSS, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK / PostgreSQL / Hibernate Reactive (Panache) — versions pinned by the shared `openbank.quarkus-service` Gradle convention and `libs.versions.toml`.
- **Port:** 8118 (app HTTP), 8085 (management — health, metrics, docs).
- **Persistence:** dedicated PostgreSQL database `openbank_cards`, Flyway migrations V1..V3.
- **Outbox:** `card_outbox` → Kafka topic `openbank.cards.events` (transactional outbox, ADR-0050).
- **Idempotency:** `Idempotency-Key` header on card issue; deduplicated by a unique `idempotency_key` column on the `cards` table (a replay returns the existing card).
- **Auth:** Keycloak OIDC; mutations require `ROLE_OPERATOR` / `ROLE_ADMIN` (block additionally allows `ROLE_COMPLIANCE`); reads allow `ROLE_VIEWER`.
- **Money-path:** NO — `card-issuance` is not in `rules.yaml: money_path_services` (no money movement; it manages card metadata and lifecycle state only).
