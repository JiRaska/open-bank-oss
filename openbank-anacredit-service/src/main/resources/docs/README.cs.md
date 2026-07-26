# openbank-anacredit-service — Dokumentace

> **Co to je:** odvozující (derive-only) služba regulatorního výkaznictví, která sestavuje **úvěrový datový soubor AnaCredit** (Reg. (EU) 2016/867, sbírá ČNB) k měsíčnímu referenčnímu datu z evidovaných úvěrových expozic — nejdříve kontokorenty ([ADR 0037](../../../../docs/adr/0037-anacredit-credit-exposure-reporting.md)). **Co to NENÍ:** služba pohybující penězi (nic neúčtuje, neemituje žádné události), není zdrojem pravdy o úvěrových nástrojích (to je `lending-service` / `balance-service`) a není přenosovým kanálem pro odeslání do ČNB (ve v1 žádný SDMX kanál).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, regulatorika | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, render pipeline |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, model chyb, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Datový model, pravidla způsobilosti, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, FinOps tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | AnaCredit, DORA, GDPR mapping, datové toky |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / RESTEasy Reactive + Jackson + reaktivní Panache/Postgres
- **Port:** 8137 (app i management na stejném portu)
- **Persistence:** **PostgreSQL** (`openbank_anacredit`, governance schema label `anacredit_schema`, ADR-0037 v2 — vzor `openbank-product-catalog`). Tabulka `credit_exposures`, Flyway migrace, reaktivní Panache adaptér.
- **Outbox / události:** **žádné** — derive-only, služba neemituje žádné doménové události ani žádné nekonzumuje.
- **Idempotence:** žádná — registrace expozice je `upsert` klíčovaný `instrumentId` (přirozeně idempotentní); čtení jsou čistá.
- **Auth:** Keycloak OIDC, role `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_COMPLIANCE`, `ROLE_API`.
- **Money-path:** **Ne** — mimo bránu ADR-0030 (bez požadavku na threat model / 2 schválení).
