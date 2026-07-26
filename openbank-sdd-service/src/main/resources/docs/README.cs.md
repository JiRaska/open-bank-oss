# openbank-sdd-service — Dokumentace

> **Co to je:** služba **SEPA inkasa na straně plátce (debtora)** (ADR-0036) — trezor mandátů klíčovaný rulebookovou dvojicí `(creditorIdentifier, UMR)`, životní cyklus mandátu, fail-closed politika autorizace inkasa a posuzovač lhůty pro vrácení (refund). **Co to NENÍ:** **nepřevádí peníze** (žádné odepsání ani zaúčtování refundu — to je delegováno na ledger/platební cestu), **není** to služba na straně příjemce (creditora) pro vystavování inkas a **není** to český tuzemský nástroj *souhlas/povolení k inkasu* (CERTIS).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, tok outboxu |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, model chyb, idempotence |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | PSD2, GDPR, DORA, AML, mapování EPC rulebooku |

## TL;DR

- **Tech stack:** Kotlin / Quarkus (RESTEasy Reactive) / JDK 25 / PostgreSQL / Hibernate Reactive (Panache) / SmallRye Kafka — reaktivně (`Uni`), nikoli suspend.
- **Port:** 8129 (aplikace), 8086 (management — root path `/q`).
- **Persistence:** PostgreSQL databáze `openbank_sdd`, Flyway migrace `V1` (tabulky `sdd_mandate`, `sdd_outbox`).
- **Outbox:** `sdd_outbox` → Kafka topic `openbank.sdd.event` (kanál `sdd-events-out`).
- **Idempotence:** idempotence přes přirozený klíč — opětovná registrace stejného `(creditorIdentifier, UMR)` vrátí uložený mandát. Žádná hlavička `Idempotency-Key` / Redis.
- **Auth:** Keycloak OIDC (RS256 JWT); mutace vyžadují `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS`/`ROLE_API`, čtení navíc povoluje `ROLE_VIEWER`.
- **Money-path:** **Ne** — `openbank-sdd-service` není v `rules.yaml: money_path_services`; v1 nikdy neprovádí nevratné zaúčtování.
