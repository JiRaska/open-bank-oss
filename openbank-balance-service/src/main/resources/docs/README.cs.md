# openbank-balance-service — Dokumentace

> **Co to je:** autoritativní zdroj **zůstatků** pro každý účet × měnu. Drží booked / available / reserved / pending částky + holdy + povolený debet. **Co to NENÍ:** evidence účtů (`account-service`) ani transakční ledger (`ledger-service`).

Self-publish přes `/q/openbank/docs` (Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## Obsah

| Sekce | Pro koho |
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
- **Schema:** `balance` v openbank cluster; tabulky `balances`, `balance_holds`, `balance_outbox`
- **Outbox:** `balance_outbox` → Kafka `openbank.balance.events`
- **Konzumenty:** account-service (cache update), notification-service (low-balance alerty)
- **Producenty pro nás:** transaction-service event-driven update zůstatku po každé transakci
