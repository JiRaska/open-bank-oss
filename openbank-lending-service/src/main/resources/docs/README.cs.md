# openbank-lending-service — Dokumentace

> **Co to je:** ohraničený kontext úvěrování (lending / credit) — vznik úvěru (origination, čtyřoč princip maker-checker), správa (splátkové kalendáře, akruální úročení), evidence zajištění a tvorba opravných položek dle IFRS 9 (ADR-0028). **Co to NENÍ:** podvojná účetní kniha (to je `openbank-ledger-service`); úvěrová kniha nikdy nedrží zůstatky — peněžní události posílá jako účetní zápisy do ledgeru.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Engineering, tech leadi | C4 diagramy, hexagonální vrstvy, outbox flow |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, čtyřoč princip, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, AML, IFRS 9, EBA mapování |
| [07 — Kalibrace rizikového modelu](./07-risk-model-calibration.md) | Úvěrové riziko, model risk, audit | Původ PD/LGD, verzování, kalibrační replay, poctivé limity |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8126 (app), 8086 (management, root-path `/q`)
- **Perzistence:** dedikovaná databáze `openbank_lending` (governance název schématu `lending_schema`), Flyway migrace V1..V3
- **Outbox:** `lending_outbox` → Kafka topic `openbank.lending.events`
- **Účetní zápisy:** nikdy nemění zůstatky — posílá vyvážené podvojné zápisy do `ledger-service` (`POST /api/v1/journals`), aktivováno build-time přepínačem `lending.ledger.backend=rest`
- **Auth:** Keycloak OIDC; role `ROLE_LENDING_OFFICER`, `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN`
- **Money-path:** ano — vyžaduje 2 schválení + threat model (`docs/threat-models/openbank-lending-service.md`)
