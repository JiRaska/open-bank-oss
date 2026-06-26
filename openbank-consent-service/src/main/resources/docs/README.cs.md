# openbank-consent-service — Dokumentace

> **Co to je:** systém záznamu pro **souhlasy se sdílením dat a iniciací plateb** (PSD2 AIS/PIS/CBPII oprávnění plus delegační scopy pro AI agenty), které zákazník uděluje třetí straně nebo agentovi. **Co to NENÍ:** SCA engine (to je `openbank-sca-service`), brána protokolu PSD2 (`psd2-service`), ani data o účtech/zůstatcích, která chrání (`openbank-account-service`, `openbank-balance-service`).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapování |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8106 (app), 8085 (management — health, metriky, docs)
- **Perzistence:** vyhrazená databáze `openbank_consents` (tabulky v `public`), Flyway migrace V1..V4
- **Outbox:** `consent_outbox` → Kafka topic `openbank.consent.events`
- **Idempotence:** klíč odvozený z `tppTransactionId` (fallback `X-Request-ID`) → Redis (TTL 24 h) při vytvoření souhlasu
- **Auth:** Keycloak OIDC (RS256 JWT); autorizace přes OPA sidecar (ADR 0034, defaultně advisory); `@Authorize` na revoke
- **SCA:** aktivace vyžaduje `COMPLETED` SCA výzvu z `openbank-sca-service` (ADR 0021)
- **Money-path služba:** ano — každá změna vyžaduje 2 schválení + threat model
