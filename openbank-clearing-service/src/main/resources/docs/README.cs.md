# openbank-clearing-service — Dokumentace

> **Co to je:** služba pro **clearing a zúčtování (settlement)** plateb — seskupuje jednotlivé platby do dávek podle platebního railu, spouští zúčtovací cykly, počítá čisté pozice za jednotlivé účastníky a sleduje životní cyklus každé clearingové položky (PENDING → IN_CLEARING → SETTLED). **Co to NENÍ:** neiniciuje platby (to dělá `sepa-payment` / `domestic-payment` / `sepa-instant` / `swift`), nevede zůstatky (`balance-service`) ani neúčtuje podvojné zápisy (`ledger-service`).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při zobrazení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, role, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK / PostgreSQL 16 / Hibernate Reactive (Panache) + reactive PG klient
- **Port:** 8124 (aplikační HTTP), 8085 (management — health/metrics, root-path `/q`)
- **Persistence:** dedikovaná PostgreSQL databáze `openbank_clearing`, Flyway migrace V1..V4
- **Outbox:** `clearing_outbox` → Kafka topic `openbank.clearing.batch.event` (kanál `clearing-events-out`); dispatcher pollu­je každých 5 s
- **Idempotence:** hlavička `Idempotency-Key` povolená (CORS); Redis (Valkey) je zapojen jako závislost
- **Auth:** Keycloak OIDC; per-operace `@RolesAllowed` (least-privilege); `settle` navíc `@Authorize` přes OPA (advisory)
- **Money-path:** **ANO** — služba je v `rules.yaml: money_path_services` (vyžaduje 2 schválení + threat model)
