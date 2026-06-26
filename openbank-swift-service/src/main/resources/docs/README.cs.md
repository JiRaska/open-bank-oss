# openbank-swift-service — Dokumentace

> **Co to je:** služba, která zaznamenává, validuje, sleduje a odesílá **SWIFT MT zprávy** (MT103, MT202, MT900/910/940/950, MT199) pro přeshraniční, vysokohodnotové platební instrukce (wire). **Co to NENÍ:** není to engine pro iniciaci plateb (to vlastní SEPA/tuzemské platební služby), není to účetní kniha (`openbank-ledger-service`), není to engine sankčního/AML screeningu (`openbank-sanctions-service` / `openbank-aml-service`) a neudržuje zůstatky účtů.

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) — reaktivní PG klient, SmallRye Kafka, SmallRye Fault Tolerance, OpenTelemetry, Micrometer/Prometheus
- **Port:** 8122 (aplikace), 8085 (management, root-path `/q`)
- **Perzistence:** dedikovaná databáze `openbank_swift` (deklarované logické schéma `swift_schema`), Flyway migrace V1..V3
- **Outbox:** tabulka `swift_outbox` → Kafka kanál `swift-events-out` → topic `openbank.payments.swift.event`
- **Idempotence:** klientem dodaný `idempotencyKey` v příkazu send, deduplikováno přes `UNIQUE` constraint v `swift_messages` (zapojen je i Redis klient)
- **Auth:** Keycloak OIDC (klient `openbank-services`); OPA sidecar autorizace (ADR-0034) ve výchozím advisory režimu (`authz.enforce=false`), `@Authorize` na akci acknowledge
- **Klasifikace:** **money-path** služba (`rules.yaml: money_path_services`) — vyžaduje 2 schválení + threat model ([threat model](../../../../docs/threat-models/openbank-swift-service.md))
