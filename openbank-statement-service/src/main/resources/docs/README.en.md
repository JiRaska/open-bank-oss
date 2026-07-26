# openbank-statement-service — Documentation

> **What it is:** the account-statement service for the multi-currency current account (ADR-0035). It performs the per-pocket monthly **period-close**, persists the small legal record (sequence + balance anchors), and renders camt.053.001.08 / MT940 / PDF statements **on demand**. **What it is NOT:** the balance engine (that's `openbank-balance-service`), the transaction ledger (`openbank-transaction-service`), nor the producer of the PAD Art. 5 annual statement of fees (that's the fee/billing domain).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, close + render + outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, fail-closed close, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO, scheduled close cadence |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka) — reactive (Mutiny `Uni`), not suspend.
- **Port:** 8136 (app), 8085 (management, root-path `/q`).
- **Persistence:** dedicated database `openbank_statement`, Flyway migrations V1..V3. Only the period-close record is stored — never rendered statement bytes.
- **Outbox:** `statement_outbox` → Kafka topic `openbank.statement.event` (event `account.statement.period.closed.v1`); inbound projection from `openbank.accounts.account.created`.
- **Idempotency:** period-close is idempotent on `(accountId, pocketCurrency, periodFrom, periodTo)` — a re-run returns the existing close, never a new legal sequence.
- **Auth:** Keycloak OIDC; reads allow `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_AUDITOR`/`ROLE_API`, mutations (close, manual run) require `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`. Outbound upstream reads use a client-credentials M2M token.
- **Money-path:** No — statement-service is not in `rules.yaml: money_path_services` (it reconciles against, but does not move, money).
