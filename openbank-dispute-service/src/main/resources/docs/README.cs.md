# openbank-dispute-service — Dokumentace

> **Co to je:** systém záznamu pro **platební reklamace a chargebacky** — otevírá reklamaci proti transakci, sbírá důkazy, sleduje životní cyklus stavu/řešení a vede append-only časovou osu. **Co to NENÍ:** NEvrací peníze ani neúčtuje zápisy (to dělá `ledger-service` / `transaction-service`), NEclearuje chargebacky kartových schémat s externí sítí a NErozhoduje o podvodu (to dělá fraud/AML pipeline) — zaznamenává workflow a vydává události.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýrství, tech leads | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, chybový model, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 21+ / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Port:** 8135 (aplikace), 8085 (management — health, metriky, docs, root-path `/q`)
- **Perzistence:** PostgreSQL databáze `openbank_dispute`, Flyway migrace V1..V4
- **Outbox:** tabulka `dispute_outbox` → Kafka topic `openbank.disputes.dispute.event` (kanál `dispute-events-out`)
- **Idempotence:** `Idempotency-Key` je povolen v CORS; Redis klient je k dispozici, ale vynucení **zatím není zapojeno** (TBD) — otevření používá generovanou referenci `DSP-<epochMillis>`
- **Autentizace:** Keycloak OIDC (realm `openbank`, klient `openbank-services`); role `ROLE_VIEWER` (čtení), `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API` (zápisy); OPA/`@Authorize` poradní režim (ADR-0034)
- **Money-path:** **Ne** — není uvedena v `rules.yaml: money_path_services` (1 schválení, threat model není vyžadován)
