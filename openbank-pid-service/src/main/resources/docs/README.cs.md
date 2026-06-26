# openbank-pid-service — Dokumentace

> **Co to je:** služba sjednocené identity (Party Identity Data) — system of record o tom, *kdo daný subjekt je* (fyzická osoba, právnická osoba, OSVČ): základní identitní atributy, externí identifikátory (bankID, ROB/AIFO, IČO, Keycloak), KYC/AML atributy, adresy, kontaktní údaje a vztahy (role), které subjekt s bankou má. **Co to NENÍ:** NEzakládá ani nedrží účty (`openbank-account-service`), sama NEvede rozhodovací KYC/AML proces (`kyc-service` / `aml-service` / `sanctions-service`) a NEvydává autentizační tokeny (to dělá Keycloak).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Engineering, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, chybový model, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML, eIDAS |

## TL;DR

- **Tech stack:** Kotlin / Quarkus / JDK 25 / PostgreSQL / Hibernate Reactive (Panache) + reaktivní PG klient
- **Port:** 8105 (aplikace), 8085 (management — health, metriky, OpenAPI, docs)
- **Perzistence:** vyhrazená databáze `openbank_pid`, Flyway migrace V1..V4
- **Outbox:** tabulka `pid_outbox`, odbavována každých 5 s s fault-tolerance (circuit breaker / retry / timeout); doménové události se navíc publikují přímo do Kafka topicu `party.events`
- **Idempotence:** dnes bez cache hlavičky `Idempotency-Key` (vytvoření je deduplikováno přes unikátní bankID `sub`); viz [03 — API](./03-api.md)
- **Autentizace:** Keycloak OIDC (RS256 JWT), role `openbank-employee` / `openbank-admin` / `openbank-customer`; OPA `@Authorize` v advisory režimu (ADR-0034)
- **Money-path:** NE — pid-service není v `rules.yaml: money_path_services`; jde o identitní / referenční službu (`dataClassification: restricted`)
