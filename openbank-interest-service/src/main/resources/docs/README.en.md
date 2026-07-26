# openbank-interest-service — Documentation

> **What it is:** the service that computes daily **interest accrual**, **capitalizes** accrued interest at the end of a period, manages **interest rate configurations**, and applies the Czech **final withholding tax** on credit interest at capitalization (§36/§38d ZDP). It also assembles the **monthly withholding-tax remittance** batch (*Vyúčtování daně vybírané srážkou*). **What it is NOT:** it does NOT hold balances (that's `openbank-balance-service`), does NOT post double-entry bookkeeping (that's `openbank-ledger-service`), and does NOT move cash to the tax authority — the cash leg is delegated downstream via an event.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO, FinOps tier |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | ZDP withholding, DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK / PostgreSQL 16 / Hibernate Reactive (Panache) — reactive (Mutiny) end-to-end
- **Port:** 8125 (app HTTP), 8085 (management — health, metrics, docs)
- **Persistence:** dedicated PostgreSQL database `openbank_interest`, Flyway migrations V1..V5
- **Outbox:** `interest_outbox` → Kafka topic `openbank.interest.accrual.event` (events `interest.withholding.recorded.v1`, `interest.withholding.remitted.v1`)
- **Idempotency:** mutations are not `Idempotency-Key`-gated in v1; the remittance assembly is idempotent by `(year, month)` (one batch per tax period)
- **Auth:** Keycloak OIDC; reads require `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`, mutations require `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`
- **Tax:** Czech final withholding on CZK credit interest (15 % resident individual; 35 % non-cooperating state; treaty rate; legal entities not withheld) — ADR-0033
- **Money path:** **No** — `interest-service` is not in `rules.yaml: money_path_services` (it moves no cash; the cash leg is delegated)
- **Release version:** see `version.txt` (0.3.0 at time of writing); **API contract version** `1.2.0` ⇒ URL `/api/v1` (ADR-0048)
