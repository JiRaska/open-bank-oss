# openbank-ledger-service — Documentation

> **What it is:** the bank's **double-entry general ledger** — the golden source of accounting truth (GL accounts, balanced journal entries, trial balance, per-customer sub-ledger). **What it is NOT:** the customer-facing balance read-model (that's `openbank-balance-service`, a projection — ADR-0039), nor the payment orchestrator (`openbank-transaction-service` posts to the ledger).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, partitioning, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, CNB accounting law mapping |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Classification:** **money-path** service ([rules.yaml](../../../../openbank-libs/governance/rules.yaml) `money_path_services`), T0 tier — 2 approvals + threat model required for changes
- **Port:** 8101 (app), 8085 (management `/q`)
- **Persistence:** dedicated PostgreSQL database `openbank_ledger`, Flyway migrations V1..V8; `journal_entries` is RANGE-partitioned by `entry_date` (per-year)
- **Outbox:** `ledger_outbox` → Kafka topic `openbank.ledger.journal.posted` (regulatory-grade dispatch, ADR-0050)
- **Idempotency:** `idempotencyKey` field on the post-journal request → `ledger_idempotency` table (DB-backed, not Redis)
- **Auth:** Keycloak OIDC (RS256 JWT). Reads gated to `ROLE_API`/`ROLE_AUDITOR`/`ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`; posting/reversing/FX-revaluation are `ROLE_OPERATOR` only. No endpoint is unauthenticated (ADR-0018).


## Monthly closing balances and movements

The period APIs expose two distinct accounting quantities under `/api/v1/ledger/periods/MONTH/{date}`. The date selects its containing month; closing balances use that month's end.

| Read | Quantity and evidence |
|---|---|
| `/trial-balance` | Selected month's journal movements |
| `/frozen-trial-balance` | Selected month's hash-verified frozen movements |
| `/closing-balance` | Live cumulative journal totals through month end |
| `/frozen-closing-balance` | Cumulative totals assembled from hash-verified frozen monthly movements |

Closing-balance reads support `MONTH`. The frozen closing balance requires the target month to have `FROZEN`/`LINES_V1` evidence, verifies each included monthly snapshot, and ties the sum to the cumulative journal totals per account and currency. Incomplete or invalid frozen evidence returns 409. The journal is a reconciliation guard; returned amounts come from the frozen snapshots. Legacy `HASH_ONLY` evidence cannot supply closing-balance lines.

FINREP balance-sheet templates and COREP own funds use the closing-balance reads. FINREP F02.00 continues to read one month's movements. Consumers that already sum period movements must retain the movement endpoint; adding cumulative balances together would double-count earlier activity. Live reads do not certify a frozen close.
