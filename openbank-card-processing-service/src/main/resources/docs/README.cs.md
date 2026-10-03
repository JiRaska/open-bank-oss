# openbank-card-processing-service — Dokumentace

> **Co to je:** **peněžní cesta karet** (ADR-0283). Přijímá autorizaci karetní transakce od acquirera, žádá card-issuance o rozhodnutí, blokuje schválenou částku (hold), započítává proti ní clearingové prezentace, uvolňuje to, co nikdy prezentováno nebylo, a zaúčtuje zúčtovanou útratu na railu `CARD` přes transaction-service. Hostí také na schématu nezávislé capability porty (BIN lookup, data obchodníků, tokenizace, reklamace) se simulátorem pro každý z nich a nad nimi **zrcadlo síťových tokenů** (`/api/v1/card-tokens`) a **reklamační desk** (`/api/v1/card-disputes`). **Co to NENÍ:** issuer-processor (žádné 3-D Secure ACS, žádný PIN/HSM, žádné živé napojení na karetní schéma) ani trezor PANů — **nepřijímá, neukládá ani neloguje PAN, CVV ani jiné kartové údaje**.

Tuto dokumentaci publikuje služba sama na management endpointu `/q/openbank/docs` (Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá pro stránku Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá a nedělá, volající, závislosti |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, tok autorizace a clearingu, porty schémat |
| [03 — API](./03-api.md) | Integrátoři, vývojáři služeb | REST kontrakt, idempotence, model odmítnutí |
| [04 — Data](./04-data.md) | Data, DBA | Schéma, invarianty, migrace, retence |
| [05 — Provoz](./05-operations.md) | DevOps, SRE | Build, konfigurace, metriky, plánovače, runbooky |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Rozsah PCI DSS, mapování GDPR, PSD2, DORA |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / PostgreSQL / Hibernate Reactive (Panache), přes sdílenou Gradle konvenci `openbank.quarkus-service`.
- **Porty:** 8157 (aplikační HTTP), 8085 (management — health, metriky, docs).
- **Persistence:** PostgreSQL databáze `openbank_card_processing`, Flyway migrace V1..V3 (`migrate-at-start: true`).
- **Outbox:** `card_outbox` → Kafka topic `openbank.card.processing.events` (ADR-0050). Události: `card.authorised.v1`, `card.declined.v1`, `card.cleared.v1`, `card.hold_released.v1`, `card.token.provisioned.v1`, `card.token.status_changed.v1`, `card.dispute.opened.v1`, `card.dispute.evidence_submitted.v1`, `card.dispute.status_changed.v1`.
- **Idempotence:** hlavička `Idempotency-Key` je povinná u autorizace i clearingu; opakovaný klíč autorizace vrátí první autorizaci (UNIQUE index).
- **Auth:** Keycloak OIDC; každý endpoint vyžaduje `ROLE_API`, `ROLE_OPERATOR` nebo `ROLE_ADMIN` a OPA akci `@Authorize` (poradní, dokud `AUTHZ_ENFORCE=false`). Sandbox acquirer je jen pro `ROLE_ADMIN` a ve výchozím stavu vypnutý.
- **Vazby na schémata:** výchozí je `simulator`. BIN lookup lze přepnout na `visa` nebo `mastercard` (sandbox API); tokenizace a reklamace **nemají vendor adaptér** — volba `visa`/`mastercard` odpoví `NOT_BOUND`, což token a reklamační endpointy vracejí jako `SCHEME_UNAVAILABLE` (409).
- **Money-path:** threat model ji považuje za money-path (`docs/threat-models/openbank-card-processing-service.md`); v době psaní ale **ještě není uvedena** v `rules.yaml: money_path_services`.
