# openbank-fx-service — Dokumentace

> **Co to je:** devizová služba, která drží FX kurzy (interní + kurz devizového trhu ČNB) a provádí měnové konverze se synchronní sankční prověrkou (ADR-0032). **Co to NENÍ:** výpočet zůstatků (`openbank-balance-service`), podvojné účetnictví (`openbank-ledger-service`), ani iniciátor plateb — nepřevádí peníze na účtech, pouze spočítá a zaeviduje konverzi.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde je v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leads | C4 diagramy, hexagonální vrstvy, outbox tok, screening gate |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML/sankce |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Port:** 8119 (app), 8085 (management, root-path `/q`)
- **Perzistence:** PostgreSQL databáze `openbank_fx` (logické schéma `fx_schema`), Flyway migrace V1..V3
- **Outbox:** `fx_outbox` → Kafka topic `openbank.fx.conversion.completed`
- **Idempotence:** hlavička `Idempotency-Key` (povinná u `POST /convert`) → deduplikace přes unique constraint `fx_conversions.idempotency_key`
- **Auth:** Keycloak OIDC; role `ROLE_VIEWER` / `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS` (konverze vyžaduje OPERATOR/ADMIN/PAYMENTS)
- **Money-path:** ano (`rules.yaml: money_path_services`) — vyžaduje 2 schválení + threat model
