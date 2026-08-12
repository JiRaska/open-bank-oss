# openbank-product-catalog — Dokumentace

> **Co to je:** systém záznamu pro **bankovní produkty a ceny** — produktový master (spořicí, běžné, úvěr, hypotéka, kreditní karta, termínovaný vklad, povolený debet, investiční) a celobankovní sazebník poplatků. **Co to NENÍ:** nezakládá účty (`openbank-account-service`), nepřesouvá peníze ani nepočítá úroky (`ledger`/`interest`/`balance`) a **není** to služba na peněžní cestě (money-path).

Tuto dokumentaci publikuje přímo služba na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Engineering, tech leadi | C4 diagramy, hexagonální vrstvy, aktuální model perzistence |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, chybový model, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Produktový model, sazebník, stav perzistence, retence |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release engineering | Build, deploy, FinOps tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, spotřebitelský úvěr, MiFID II, transparentnost |

## TL;DR

- **Tech stack:** Kotlin 2.3 / Quarkus 3.33 LTS / JDK 25 / RESTEasy Reactive + Jackson / SmallRye OpenAPI + Health
- **Port:** 8104 (aplikace), 8085 (management health/metrics)
- **Perzistence:** PostgreSQL přes reactive Panache; schéma spravuje Flyway a 15 bankovních příkladů se seeduje pouze do prázdné databáze (viz [04 — Data](./04-data.md)).
- **Outbox / události:** žádné — služba nepublikuje Kafka události a nemá outbox.
- **Souběh:** mutace vracejí ETag; klient může poslat `If-Match` a zastaralá revize vrátí 409.
- **Autentizace:** OIDC resource server; čtení vyžaduje autentizaci a zápis role OPERATOR/ADMIN. OPA je v bankovním profilu zatím advisory. Viz [03 — API](./03-api.md) a [06 — Compliance](./06-compliance.md).
- **Money-path:** **Ne** (není v `rules.yaml: money_path_services`).
- **API kontrakt:** `openapi.yaml` přítomen, `info.version` 1.1.0, base path `/api/v1` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
