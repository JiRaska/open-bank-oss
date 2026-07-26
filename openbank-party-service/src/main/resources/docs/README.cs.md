# openbank-party-service — Dokumentace

> **Co to je:** systém záznamů o **party** — fyzických osobách a právnických osobách (zákazníci, OSVČ, společnosti, trusty), se kterými má banka vztah, včetně jejich identifikačních dokladů a stavu KYC/AML životního cyklu. **Co to NENÍ:** registr účtů (`openbank-account-service`), engine KYC případů (`openbank-kyc-service`), engine AML screeningu (`openbank-aml-service`) ani šifrovaný trezor rodných čísel (`openbank-pid-service`).

Tuto dokumentaci služba publikuje přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýrství, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 21+ / PostgreSQL 16 / Hibernate Reactive (Panache) + reaktivní PostgreSQL klient
- **Port:** 8111 (app/HTTP), 8085 (management — health, metriky, docs)
- **Persistence:** dedikovaná PostgreSQL databáze `openbank_parties`, Flyway migrace V1..V8
- **Outbox:** `party_outbox` → Kafka topic `openbank.party.events` (dispatcher pollne každých 5 s s fault-tolerancí: circuit breaker + retry + bulkhead + timeout)
- **Příchozí eventy:** konzumuje `openbank.kyc.events` a `openbank.aml.events` pro řízení dvouklíčové KYC+AML aktivační brány
- **Idempotence:** hlavička `Idempotency-Key` je povinná na `POST /api/v1/parties`; unikátní omezení na e-mail navíc deduplikuje party (409 při replay)
- **Auth:** Keycloak OIDC, role `ROLE_VIEWER` / `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_KYC` / `ROLE_API`; OPA authz v advisory režimu (ADR-0034)
- **Klasifikace dat:** `restricted` — služba drží PII (jméno, e-mail, telefon, adresu, národnost, DIČ). Není to money-path služba.
