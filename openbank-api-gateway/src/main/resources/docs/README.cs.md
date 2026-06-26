# openbank-api-gateway — Dokumentace

> **Co to je:** tenká **north-south API brána** (Kong OSS, bez databáze) stojící před dockerizovaným stackem OpenBank s jedním veřejným proxy endpointem a explicitním směrováním na jednotlivé služby. **Co to NENÍ:** byznysová služba — nevlastní žádnou doménu, databázi, Kafka topic ani outbox; sama tokeny neověřuje (autentizace je *passthrough* — navazující Quarkus služby si dál ověřují Keycloak OIDC).

Tato dokumentace je publikována v rámci vzoru Docs-as-Service (viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Na rozdíl od Quarkus služeb je brána kontejner Kong a nemá vlastní management endpoint `/q/openbank/docs`; tyto soubory jsou zdroj pravdy pro stránku Service Docs v admin UI.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co brána dělá, kdo ji volá, kde sedí v topologii |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | Kong DB-less konfigurace, model směrování, passthrough autentizace |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | Proxy/admin povrch, tabulka rout, chybový model, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Proč zde není datové úložiště; deklarativní konfigurace jako jediný stav |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, FinOps tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, NIS2 pro edge komponentu |

## TL;DR

- **Tech stack:** Kong OSS `3.7.1`, DB-less (deklarativní) režim, Docker Compose pro lokální běh
- **Porty:** `8000` (proxy), `8001` (admin API) — přepsatelné přes `KONG_PROXY_PORT` / `KONG_ADMIN_PORT`
- **Perzistence:** **žádná** — `KONG_DATABASE=off`; jediný stav je `kong/kong.yml` (`_format_version: "3.0"`)
- **Outbox / Kafka:** nepoužito — brána neemituje žádné doménové události
- **Idempotence:** na bráně nepoužito; idempotenci vlastní navazující služby (`Idempotency-Key`)
- **Autentizace:** **passthrough** (výchozí) — předává `Authorization`, `X-Request-Id`, `X-Correlation-Id` beze změny; token ověřují navazující služby. Volitelné placeholdery pro Kong OSS `jwt` plugin jsou v `.env.example`.
- **Směrované služby:** 14 upstreamů na `host.docker.internal:8100–8117` (account, ledger, transaction, balance, consent, psd2, agent, party, notification, audit, kyc, sepa, domestic, aml)
