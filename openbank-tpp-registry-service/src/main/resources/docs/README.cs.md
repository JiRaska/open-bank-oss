# openbank-tpp-registry-service — Dokumentace

> **Co to je:** registr poskytovatelů třetích stran (TPP) autorizovaných podle PSD2 — licencované instituce AISP/PISP/PIISP, jejich eIDAS certifikáty, stav registrace a stav blacklistu. Odpovídá na otázku „smí tento TPP právě teď provádět AIS/PIS/PIIS?". **Co to NENÍ:** úložiště souhlasů (to je `openbank-consent-service`), engine silného ověření zákazníka (`openbank-sca-service`), ani samotná Open Banking fasáda (`openbank-psd2-service`).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co tu najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, NIS2 |

## TL;DR

- **Tech stack:** Kotlin 2.3.20 / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8108 (aplikace), 8085 (management — `/q`)
- **Perzistence:** PostgreSQL databáze `openbank_tpp_registry`, Flyway migrace V1/V3/V4
- **Outbox:** `tpp_outbox` → Kafka topic `openbank.tpp.registry.event` (kanál `tpp-events-out`); `TPP_REGISTERED` / `TPP_BLACKLISTED` se zapisují v transakci se změnou stavu (issue #4007, viz [02](./02-architecture.md))
- **Idempotence:** hlavička `Idempotency-Key` → Redis (přes `openbank-libs` `IdempotencyStore`)
- **Auth:** Keycloak OIDC, OPA sidecar autorizace (ADR-0034) v advisory režimu (`AUTHZ_ENFORCE=false` defaultně); `@Authorize` na mutaci blacklist
- **Schopnost:** Open Banking (PSD2) — **není** money-path služba
