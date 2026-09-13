# openbank-audit-service — Documentation

> **What it is:** the platform-wide **immutable audit trail** — it consumes domain events from across the fleet (account, transaction, balance, party, kyc, consent) and persists them as a tamper-resistant, append-only event history queryable per aggregate. **What it is NOT:** a business service that owns any aggregate of its own, nor an authorization/SIEM engine — it records what happened, it does not decide policy.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who feeds it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, consume flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, role gating |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, immutability, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, EBA ICT, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 (LTS) / JDK / PostgreSQL / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Port:** 8113 (app), 8085 (management, root-path `/q`)
- **Persistence:** PostgreSQL database `openbank_audit`, table `audit_entries` (append-only, `public` schema), Flyway migrations V1..V16
- **Ingest:** Kafka consumer `audit-events-in` over topics `openbank.accounts.account.created`, `openbank.transactions.transaction.initiated`, `openbank.balance.events`, `openbank.party.events`, `openbank.kyc.events`, `openbank.consent.events`
- **Outbox:** none — no domain event of its own to publish; the dead `audit_outbox` re-emit apparatus was removed (#5126)
- **Idempotency:** no `Idempotency-Key` header — write path is event-driven, each entry carries a unique `entry_id` UUID
- **Auth:** Keycloak OIDC; read API gated to roles `ROLE_AUDITOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE`
- **Money-path:** No (not in `rules.yaml: money_path_services`)
