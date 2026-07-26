# openbank-statement-service — Dokumentace

> **Co to je:** služba výpisů z účtu pro vícecurrencyový běžný účet (ADR-0035). Provádí měsíční **uzávěrku období** po jednotlivých kapsách, ukládá malý právní záznam (sekvence + kotvy zůstatků) a renderuje výpisy camt.053.001.08 / MT940 / PDF **na vyžádání**. **Co to NENÍ:** zůstatkový engine (to je `openbank-balance-service`), transakční účetní kniha (`openbank-transaction-service`), ani producent ročního výkazu poplatků dle PAD čl. 5 (ten patří doméně poplatků/billingu).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co tam najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Engineering, tech leadi | C4 diagramy, hexagonální vrstvy, tok uzávěrky + renderu + outboxu |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, fail-closed uzávěrka, model chyb, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO, plánovaná kadence uzávěrek |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka) — reaktivně (Mutiny `Uni`), ne suspend.
- **Port:** 8136 (aplikace), 8085 (management, root-path `/q`).
- **Perzistence:** dedikovaná databáze `openbank_statement`, Flyway migrace V1..V3. Ukládá se pouze záznam uzávěrky období — nikdy ne vyrenderované bajty výpisu.
- **Outbox:** `statement_outbox` → Kafka topic `openbank.statement.event` (událost `account.statement.period.closed.v1`); příchozí projekce z `openbank.accounts.account.created`.
- **Idempotence:** uzávěrka období je idempotentní na `(accountId, pocketCurrency, periodFrom, periodTo)` — opakované spuštění vrátí existující uzávěrku, nikdy ne novou právní sekvenci.
- **Autentizace:** Keycloak OIDC; čtení povoluje `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_AUDITOR`/`ROLE_API`, mutace (uzávěrka, manuální běh) vyžadují `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`. Odchozí čtení upstreamů používají M2M token client-credentials.
- **Money-path:** Ne — statement-service není v `rules.yaml: money_path_services` (rekonciliuje proti penězům, ale s nimi nehýbe).
