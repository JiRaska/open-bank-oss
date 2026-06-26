# openbank-standing-order-service — Dokumentace

> **Co to je:** platební služba, která drží definici a životní cyklus **opakovaných platebních příkazů** (trvalé příkazy) — vytvoření, pozastavení, obnovení, zrušení — a vydává doménové události, jež spouštějí navazující provedení platby. **Co to NENÍ:** sama nepřesouvá peníze (vlastní platbu zakládá `transaction-service` / služby SEPA/tuzemských plateb), není to účetní kniha (ledger) ani engine zůstatků.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 21+ / PostgreSQL 16 / Hibernate Reactive (Panache) — verze se řeší ze sdíleného `libs.versions.toml` (viz [05 — Provoz](./05-operations.md)).
- **Port:** 8121 (aplikace), 8085 (management, root-path `/q`)
- **Persistence:** dedikovaná databáze `openbank_standing_orders`, Flyway migrace V1..V3
- **Outbox:** `standing_order_outbox` → Kafka topic `openbank.standing-orders.order.event`
- **Idempotence:** `idempotencyKey` (na požadavek, unikátní omezení v DB) → vytvoření je bezpečné při opakování
- **Autentizace:** Keycloak OIDC; autorizace přes OPA sidecar (`@Authorize`, ADR-0034) ve výchozím advisory režimu
- **Money-path:** **Ne** — služba není v `rules.yaml: money_path_services` (jeden schvalovatel, threat model se nevyžaduje)
