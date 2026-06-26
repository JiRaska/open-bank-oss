# openbank-analytics-sink — Documentation

> **What it is:** the analytics/reporting ingestion service — it consumes the platform's domain-event stream and writes a 10-year, PII-masked **bronze → silver → gold** medallion warehouse in ClickHouse (ADR-0022). **What it is NOT:** an OLTP/operational service. It owns **no PostgreSQL database**, computes no balances, executes no payments, and is **never** in a customer's request path.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who feeds it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 view, hexagonal layers, event → bronze flow, ports/adapters |
| [03 — API](./03-api.md) | Service developers, integrators | REST operator surface, roles, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | ClickHouse schema, medallion layers, retention, PII masking |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, FinOps tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, NIS2, BCBS 239 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK 25 / ClickHouse (warehouse store of record) / Kafka (SmallRye Reactive Messaging). No Hibernate, no Postgres, no Flyway (intentionally — ADR-0022).
- **Port:** 8134 (app), 8086 (management, root-path `/q`)
- **Persistence:** **no OLTP DB.** Store of record is the ClickHouse analytics warehouse (database `openbank_analytics`), DDL in `clickhouse/V1__analytics_bronze_silver.sql` (applied by an operator, not Flyway).
- **Ingest:** consumes Kafka topics `openbank.{account,transaction,balance,party,kyc,consent}.events` (consumer group `analytics-sink`, `auto.offset.reset=earliest`). **No outbox** — this service is a consumer, not a producer of domain events.
- **Idempotency:** `eventId` is the dedupe key; ClickHouse `ReplacingMergeTree` collapses duplicates (Kafka is at-least-once).
- **Auth:** Keycloak OIDC. Operator REST surface gated to `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_COMPLIANCE`; no `@PermitAll` mutations.
