# openbank-sca-service — Dokumentace

> **Co to je:** stroj pro silné ověření zákazníka (SCA) — inicializuje a ověřuje step-up autentizační výzvy (SMS OTP, TOTP, push, biometrika) a zaznamenává podpisem ověřené, dynamicky provázané out-of-band schválení ze zařízení (ADR-0021). **Co to NENÍ:** poskytovatel identity (to je Keycloak), úložiště souhlasů (`openbank-consent-service`), ani autorizátor plateb (platební služby SCA *volají*, samy zde nežijí).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při zobrazení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, outbox tok, návrh decoupled schválení |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování PSD2/RTS, DORA, GDPR, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus (LTS) / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / Redis (Valkey) / Kafka
- **Port:** 8110 (aplikace), 8085 (management — health, metriky, docs)
- **Persistence:** dedikovaná PostgreSQL databáze `openbank_sca`, Flyway migrace V1..V4
- **Outbox:** `sca_outbox` → Kafka topic `openbank.sca.challenge.event`
- **Idempotence:** hlavička `Idempotency-Key` (nebo `X-Request-ID`) → Redis (TTL 300 s), plus idempotenční klíč odvozený z příkazu v use case
- **Auth:** Keycloak OIDC; mutace chráněny `@Authorize` (OPA sidecar, ADR-0034) + vynucení vlastnictví per-party
- **Money-path služba:** ano (`rules.yaml: money_path_services`) — vyžaduje 2 schválení + threat model (ADR-0030)
- **Klíčový ADR:** [ADR 0021 — SCA decoupled schválení zařízení, nikdy auto-approve](../../../../docs/adr/0021-sca-decoupled-device-approval-no-auto-approve.md)
