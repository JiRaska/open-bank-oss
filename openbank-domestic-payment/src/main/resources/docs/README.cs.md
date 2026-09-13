# openbank-domestic-payment — Dokumentace

> **Co to je:** služba pro iniciaci a životní cyklus **tuzemských plateb v ČR** (CZK platební rails, adresace číslem účtu + kódem banky, variabilní/specifický/konstantní symbol, SIPO). Každou platbu při založení synchronně screenuje proti sankčním seznamům (ADR-0032). **Co to NENÍ:** clearingový/zúčtovací stroj (to je `clearing-service`), podvojná účetní kniha (`ledger-service`), SEPA/přeshraniční cesta (`sepa-payment`/`sepa-instant`/`swift-service`) ani autorita pro sankce/AML rozhodnutí (`sanctions-service`/`aml-service`).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

Jde o **money-path službu** (`rules.yaml: money_path_services`): změny vyžadují 2 schválení + threat model ([`docs/threat-models/openbank-domestic-payment.md`](../../../../docs/threat-models/openbank-domestic-payment.md)).

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, screeningová brána, outbox |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, ČNB mapování |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 (build na JDK 20) / PostgreSQL 16 / Hibernate Reactive (Panache) + reaktivní PG klient
- **Port:** 8116 (aplikace), 8085 (management, root-path `/q`)
- **Perzistence:** dedikovaná databáze `openbank_domestic_payments`, Flyway migrace V1..V5 (governance manifest deklaruje logické jméno schématu `domestic_schema`)
- **Outbox:** `domestic_payment_outbox` → Kafka topic `openbank.domestic.payment.events` (kanál `events-out`)
- **Idempotence:** `Idempotency-Key` je v PostgreSQL trvale svázán s normalizovaným požadavkem a aktérem
- **Auth:** Keycloak OIDC; mutace vyžadují `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`; OPA authz sidecar (ADR-0034, defaultně advisory)
- **Screening:** synchronní sankční screening jmen plátce + příjemce při založení, fail-closed (ADR-0032)
