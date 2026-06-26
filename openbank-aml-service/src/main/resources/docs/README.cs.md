# openbank-aml-service — Dokumentace

> **Co to je:** služba **správy AML případů** (Anti-Money Laundering) — zakládá, posuzuje a rozhoduje screeningové AML případy pro klienty, účty a transakce. **Co to NENÍ:** engine pro porovnávání se sankčními/PEP seznamy (to je `openbank-sanctions-service`), ani služba KYC ověření totožnosti (`openbank-kyc-service`), ani platební rozhraní — peníze nepřesouvá a platby přímo neblokuje.

Tuto dokumentaci publikuje sama služba na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde leží v doméně |
| [02 — Architektura](./02-architecture.md) | Engineering, tech leads | C4 diagramy, hexagonální vrstvy, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování AMLD, DORA, GDPR, PSD2, NIS2 |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK (build 20, runtime 25 Temurin) / PostgreSQL 16 / Hibernate Reactive (Panache) — reaktivní PG klient
- **Port:** 8117 (aplikace), 8085 (management `/q`)
- **Perzistence:** vyhrazená databáze `openbank_aml`, Flyway migrace V1..V5 (deklarovaná datová doména `compliance`, logický název schématu `aml_schema`)
- **Outbox:** `aml_outbox` → Kafka topic `openbank.aml.events` (ADR-0050 regulatory-grade dispatch)
- **Příchozí události:** konzumuje `openbank.party.events` a na `PARTY_CREATED` zakládá onboarding screeningový případ
- **Idempotence:** hlavička `Idempotency-Key` → Redis (Valkey); navíc unikátní `idempotency_key` na řádku případu
- **Auth:** Keycloak OIDC; mutace vyžadují `ROLE_OPERATOR`, `ROLE_ADMIN` nebo `ROLE_COMPLIANCE`; OPA sidecar autorizace (ADR-0034, ve výchozím stavu advisory)
- **Money-path:** **Ne** — není v `rules.yaml: money_path_services` (1 schválení, bez povinného threat modelu). Je to compliance-screeningová služba ve FinOps skupině `compliance-screening`.
