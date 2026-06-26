# openbank-balance-service — Documentation

> **What it is:** the authoritative source of **balances** per account × currency. Holds booked / available / reserved / pending amounts + holds + arranged overdraft. **What it is NOT:** the account registry (`account-service`) nor the transactional ledger (`ledger-service`).

Self-published at `/q/openbank/docs` (Docs-as-Service — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## Contents

| Section | Audience |
|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads |
| [03 — API](./03-api.md) | Service developers |
| [04 — Data](./04-data.md) | Data, analytics, DBA |
| [05 — Operations](./05-operations.md) | DevOps, SRE |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8103 (app), 8085 (mgmt)
- **Schema:** `balance` in the openbank cluster; tables `balances`, `balance_holds`, `balance_outbox`
- **Outbox:** `balance_outbox` → Kafka `openbank.balance.events`
- **Consumers:** account-service (cache update), notification-service (low-balance alerts)
- **Producers for us:** transaction-service event-driven balance update after every accepted transaction
