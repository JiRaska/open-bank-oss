# openbank-product-catalog — Dokumentace

> **Co to je:** odvětvově neutrální, schématy řízený systém záznamu produktů a cen se zachovaným bankovním kompatibilním API. **Co to NENÍ:** nekótuje, nezakládá účty ani pojistky, nepřesouvá peníze, nepočítá úroky a **není** to money-path služba.

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
- **Outbox / události:** v2 zapisuje audit a outbox atomicky; standalone integrace používají trvalý cursor `/api/v2/events`, Kafka je volitelná.
- **Souběh:** v1 přijímá `If-Match` volitelně a drží legacy 409; v2 jej vyžaduje (chybí 428, zastaralý 412).
- **Autentizace:** OIDC resource server; podporuje OpenBank role i konfigurovatelné scopes `catalog:read|author|publish`. Maker/checker vynucuje core. Viz [03 — API](./03-api.md) a [06 — Compliance](./06-compliance.md).
- **Money-path:** **Ne** (není v `rules.yaml: money_path_services`).
- **API kontrakt:** OpenAPI 3.1, `info.version` 2.0.0; kompatibilní `/api/v1` a generické `/api/v2` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
