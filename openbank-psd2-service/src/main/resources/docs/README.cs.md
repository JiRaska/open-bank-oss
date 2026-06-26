# openbank-psd2-service — Dokumentace

> **Co to je:** PSD2 / Open Banking fasáda platformy OpenBank — Account Information Service (AIS) a Payment Initiation Service (PIS) pro licencované poskytovatele třetích stran (TPP), plus životní cyklus souhlasů a vývojářský sandbox. **Co to NENÍ:** systém záznamů o účtech (`openbank-account-service`), úložiště souhlasů (`openbank-consent-service`), exekutoři plateb (`openbank-sepa-payment` / `-instant` / `-domestic-payment`) ani registr TPP (`openbank-tpp-registry-service`). Je to bezstavová překladová/orchestrační vrstva před těmito službami.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok, odolnost |
| [03 — API](./03-api.md) | Integrátoři TPP, vývojáři | Open Banking REST kontrakt, idempotence, eIDAS autentizace, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma outboxu, migrace, retence, zpracování PII |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, serverless tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování PSD2, GDPR, DORA, AML, NIS2 |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / Hibernate Reactive (Panache) — pouze outbox tabulka; jinak je služba bezstavová.
- **Port:** 8107 (aplikace), 8085 (management, root-path `/q`). *(Pozn.: `openapi.yaml` aktuálně uvádí jako lokální server port `8122` — viz [03 — API](./03-api.md).)*
- **Perzistence:** jediná transakční outbox tabulka `psd2_outbox`; žádná doménová data zde nejsou ukládána (souhlasy jsou v `consent-service`, účty v `account-service`).
- **Outbox:** `psd2_outbox` → Kafka topic `openbank.psd2.events` (dispatcher pollu je každých 5 s).
- **Idempotence:** hlavička `Idempotency-Key` u PIS (platby), `X-Request-ID` u vytvoření souhlasu → Redis (Valkey), TTL 24 h.
- **Autentizace:** autentizace TPP přes eIDAS QWAC (`SSL-CLIENT-S-DN`) nebo `X-TPP-ID`, ověřená proti `tpp-registry-service` pro roli `AISP` / `PISP`. Přístup k jednotlivým zdrojům vynucuje `consent-service`. (Keycloak OIDC je zapojen, ale nehlídá Open Banking cesty.)
- **Režim:** sandbox režim ve výchozím stavu zapnutý (`openbank.psd2.sandbox-mode=true`); downstream klienti jsou aktuálně stub implementace.
