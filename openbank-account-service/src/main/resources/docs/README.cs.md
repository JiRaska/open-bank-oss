# openbank-account-service — Documentation

> **Co to je:** core banking služba držící definici a stavy klientských účtů (běžné, spořicí, technické). **Co to NENÍ:** zůstatkový engine (to dělá `openbank-balance-service`), ani transakční ledger (`openbank-ledger-service`).

Tato dokumentace je vystavena přímo službou na management endpointu `/q/openbank/docs` (Docs-as-Service pattern — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji fetchuje při zobrazení Service Docs.

## Obsah

| Sekce | Pro koho | Co tam najdeš |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagramy, hexagonální vrstvy, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrátoři | REST kontrakt, idempotence, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrace, retence, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8100 (app), N/A (no separate mgmt port yet)
- **Persistence:** vlastní DB schema `account`, Flyway migrace V1..V6
- **Outbox:** `account_outbox` → Kafka topic `openbank.account.events.v1`
- **Idempotence:** `Idempotency-Key` header → Redis (24h TTL)
- **Auth:** Keycloak OIDC, role `ROLE_OPERATOR` pro mutace
