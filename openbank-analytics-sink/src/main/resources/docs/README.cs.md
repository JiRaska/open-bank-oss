# openbank-analytics-sink — Dokumentace

> **Co to je:** služba pro příjem dat do analytické/reportingové vrstvy — konzumuje proud doménových událostí platformy a zapisuje 10letý, PII-maskovaný **bronze → silver → gold** medailonový sklad v ClickHouse (ADR-0022). **Co to NENÍ:** OLTP/provozní služba. Nevlastní **žádnou PostgreSQL databázi**, nepočítá zůstatky, neprovádí platby a **nikdy** není v request-path zákazníka.

Tuto dokumentaci publikuje přímo služba na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji plní, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýrství, tech leads | C4 pohled, hexagonální vrstvy, tok událost → bronze, porty/adaptéry |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST operátorský povrch, role, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | ClickHouse schéma, medailonové vrstvy, retence, PII maskování |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, FinOps tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML, NIS2, BCBS 239 |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK 25 / ClickHouse (sklad jako store of record) / Kafka (SmallRye Reactive Messaging). Žádný Hibernate, žádný Postgres, žádný Flyway (záměrně — ADR-0022).
- **Port:** 8134 (app), 8086 (management, root-path `/q`)
- **Perzistence:** **žádná OLTP DB.** Store of record je ClickHouse analytický sklad (databáze `openbank_analytics`), DDL v `clickhouse/V1__analytics_bronze_silver.sql` (aplikuje operátor, ne Flyway).
- **Příjem:** konzumuje Kafka topiky `openbank.{account,transaction,balance,party,kyc,consent}.events` (consumer group `analytics-sink`, `auto.offset.reset=earliest`). **Žádný outbox** — služba je konzument, ne producent doménových událostí.
- **Idempotence:** `eventId` je dedupe klíč; ClickHouse `ReplacingMergeTree` slučuje duplicity (Kafka je at-least-once).
- **Auth:** Keycloak OIDC. Operátorský REST povrch zabezpečen na `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_COMPLIANCE`; žádné `@PermitAll` mutace.
