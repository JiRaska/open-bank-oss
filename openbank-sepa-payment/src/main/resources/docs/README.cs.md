# openbank-sepa-payment — Dokumentace

> **Co to je:** vlastník životního cyklu **SEPA úhrad (SCT)** — přijme platební instrukci, spustí synchronní sankční screening a provede platbu jejím stavovým životním cyklem, přičemž emituje události pro navazující clearing/ledger. **Co to NENÍ:** rail pro okamžité platby (`openbank-sepa-instant`), tuzemský rail (`openbank-domestic-payment`), clearingový engine (`openbank-clearing-service`), podvojná kniha (`openbank-ledger-service`) ani autorita zůstatků (`openbank-balance-service`).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji stahuje při vykreslování stránky Service Docs.

Jde o **money-path službu** (`rules.yaml: money_path_services`) — změny vyžadují 2 schválení a aktuální threat model ([`docs/threat-models/openbank-sepa-payment.md`](../../../../docs/threat-models/openbank-sepa-payment.md), ADR-0030).

## Obsah

| Sekce | Publikum | Co tam najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Vývoj, tech leadi | C4 diagramy, hexagonální vrstvy, screening gate, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML, SEPA Rulebook |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / Reactive PG client
- **Port:** 8115 (aplikace), 8085 (management, root-path `/q`)
- **Perzistence:** vyhrazená databáze `openbank_sepa_payments`, deklarované schéma `sepa_schema`, Flyway migrace V1..V4
- **Outbox:** `sepa_payment_outbox` → Kafka topic `openbank.sepa.payment.events` (vyprazdňováno každých 5 s)
- **Idempotence:** hlavička `Idempotency-Key` (povinná při create) → Redis (`libs.idempotency.IdempotencyStore`)
- **Auth:** Keycloak OIDC; role pro zápis `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`, čtení přidává `ROLE_VIEWER`; OPA advisory→enforce (ADR-0034)
- **Screening gate:** synchronní sankční screening při create, fail-closed (ADR-0032)
- **FinOps tier:** T0 — always-on (synchronní money-path skok, ADR-0057)
