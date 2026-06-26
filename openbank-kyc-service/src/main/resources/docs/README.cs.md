# openbank-kyc-service — Dokumentace

> **Co to je:** služba pro správu případů Know Your Customer / Customer Due Diligence — otevírá KYC případy, sleduje jednotlivé compliance kontroly (totožnost, adresa, PEP, sankce, nepříznivá média) a zaznamenává rozhodnutí schválit/zamítnout pod principem čtyř očí pro daného klienta (party). **Co to NENÍ:** samotný engine pro screening sankcí/PEP (to je `openbank-sanctions-service` / `openbank-aml-service`), ani master data klientů (to je `openbank-party-service`).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, error model, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování AMLD, GDPR, EBA, DORA |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) — viz `build.gradle.kts`
- **Port:** 8114 (aplikace), 8085 (management, root-path `/q`)
- **Perzistence:** PostgreSQL databáze `openbank_kyc`, Flyway migrace V1..V5
- **Outbox:** `kyc_outbox` → Kafka topic `openbank.kyc.events`
- **Příchozí události:** konzumuje `openbank.party.events` (`PARTY_CREATED` → automaticky otevře případ, ADR-0068)
- **Idempotence:** na úrovni domény — nejvýše jeden aktivní případ na party (parciální unikátní index `uq_kyc_cases_active_party`, V5); žádná cache idempotence na úrovni požadavku
- **Auth:** Keycloak OIDC; mutace vyžadují `ROLE_KYC`/`ROLE_ADMIN`; OPA advisory autorizace (ADR-0034)
