# openbank-interest-service — Dokumentace

> **Co to je:** služba, která počítá denní **úročení (accrual)**, na konci období provádí **kapitalizaci** naběhlého úroku, spravuje **konfigurace úrokových sazeb** a při kapitalizaci aplikuje českou **srážkovou daň** z úrokového výnosu (§36/§38d ZDP). Dále sestavuje **měsíční odvod sražené daně** (*Vyúčtování daně vybírané srážkou*). **Co to NENÍ:** nedrží zůstatky (to je `openbank-balance-service`), neúčtuje podvojně (to je `openbank-ledger-service`) a nepřesouvá hotovost finančnímu úřadu — peněžní část je delegována dále přes událost.

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při zobrazení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýrství, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO, FinOps tier |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | ZDP srážková daň, DORA, GDPR, PSD2, AML mapování |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 LTS / JDK / PostgreSQL 16 / Hibernate Reactive (Panache) — plně reaktivní (Mutiny)
- **Port:** 8125 (app HTTP), 8085 (management — health, metriky, docs)
- **Persistence:** dedikovaná PostgreSQL databáze `openbank_interest`, Flyway migrace V1..V5
- **Outbox:** `interest_outbox` → Kafka topic `openbank.interest.accrual.event` (události `interest.withholding.recorded.v1`, `interest.withholding.remitted.v1`)
- **Idempotence:** mutace ve v1 nejsou hlídány hlavičkou `Idempotency-Key`; sestavení odvodu je idempotentní podle `(year, month)` (jeden batch na zdaňovací období)
- **Auth:** Keycloak OIDC; čtení vyžaduje `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`, mutace vyžadují `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`
- **Daň:** česká srážková daň z CZK úrokového výnosu (15 % rezident fyzická osoba; 35 % nespolupracující stát; smluvní sazba; právnické osoby se nesráží) — ADR-0033
- **Money path:** **Ne** — `interest-service` není v `rules.yaml: money_path_services` (nepřesouvá hotovost; peněžní část je delegována)
- **Release verze:** viz `version.txt` (v době psaní 0.3.0); **verze API kontraktu** `1.2.0` ⇒ URL `/api/v1` (ADR-0048)
