# openbank-ledger-service — Dokumentace

> **Co to je:** **podvojná hlavní kniha** banky — zlatý zdroj účetní pravdy (účty hlavní knihy, vyvážené účetní zápisy, předvaha, analytická evidence po zákaznících). **Co to NENÍ:** read-model zůstatků pro zákazníka (to je `openbank-balance-service`, projekce — ADR-0039), ani orchestrátor plateb (`openbank-transaction-service` účtuje do hlavní knihy).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při zobrazení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leads | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, partitioning, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, mapování na účetní zákon ČNB |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Klasifikace:** **money-path** služba ([rules.yaml](../../../../openbank-libs/governance/rules.yaml) `money_path_services`), tier T0 — změny vyžadují 2 schválení + threat model
- **Port:** 8101 (aplikace), 8085 (management `/q`)
- **Perzistence:** dedikovaná PostgreSQL databáze `openbank_ledger`, Flyway migrace V1..V8; `journal_entries` je RANGE-partitionováno podle `entry_date` (po letech)
- **Outbox:** `ledger_outbox` → Kafka topic `openbank.ledger.journal.posted` (regulatorní dispatch, ADR-0050)
- **Idempotence:** pole `idempotencyKey` v požadavku na zaúčtování → tabulka `ledger_idempotency` (v DB, ne Redis)
- **Auth:** Keycloak OIDC (RS256 JWT). Čtení omezeno na `ROLE_API`/`ROLE_AUDITOR`/`ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`; účtování/storno/FX revalvace jen `ROLE_OPERATOR`. Žádný endpoint není neautentizovaný (ADR-0018).
