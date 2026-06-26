# openbank-card-issuance-service — Dokumentace

> **Co to je:** systém záznamu pro **životní cyklus karet** — vydává debetní/kreditní/předplacené/virtuální karty proti existujícímu účtu a klientovi (party) a řídí jejich stav (PENDING → ACTIVE → SUSPENDED / BLOCKED / EXPIRED / CANCELLED). **Co to NENÍ:** procesor autorizací/transakcí karet (neschvaluje platby na POS/ATM) ani trezor PANů (ukládá pouze maskovaný PAN — žádné celé číslo karty, CVV ani PIN).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, tok outboxu |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, PCI DSS, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK / PostgreSQL / Hibernate Reactive (Panache) — verze zafixované sdílenou Gradle konvencí `openbank.quarkus-service` a `libs.versions.toml`.
- **Port:** 8118 (aplikační HTTP), 8085 (management — health, metriky, docs).
- **Persistence:** vlastní PostgreSQL databáze `openbank_cards`, Flyway migrace V1..V3.
- **Outbox:** `card_outbox` → Kafka topic `openbank.cards.events` (transakční outbox, ADR-0050).
- **Idempotence:** hlavička `Idempotency-Key` při vydání karty; deduplikace přes unikátní sloupec `idempotency_key` v tabulce `cards` (opakování vrátí existující kartu).
- **Auth:** Keycloak OIDC; mutace vyžadují `ROLE_OPERATOR` / `ROLE_ADMIN` (block navíc povoluje `ROLE_COMPLIANCE`); čtení povoluje `ROLE_VIEWER`.
- **Money-path:** NE — `card-issuance` není v `rules.yaml: money_path_services` (žádný pohyb peněz; spravuje pouze metadata karet a stav životního cyklu).
