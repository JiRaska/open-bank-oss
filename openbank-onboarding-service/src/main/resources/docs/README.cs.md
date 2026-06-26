# openbank-onboarding-service — Dokumentace

> **Co to je:** read-only **projekce** onboardingového trychtýře (ADR-0068). Agreguje události z `party-service`, `kyc-service` a `sca-service` do denormalizovaného read-modelu pro operátorské dashboardy a cockpit. **Co to NENÍ:** systém záznamu — nikdy nevlastní stav party, KYC ani SCA, nerozhoduje o schválení KYC a (v této verzi) ještě nehostuje frontu schvalování „čtyř očí“.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, tok projekce událostí |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, chybový model, verzování |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapování |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache)
- **Port:** 8130 (app), 8085 (management, root-path `/q`)
- **Perzistence:** PostgreSQL databáze `openbank_onboarding`, jediná tabulka `onboarding_records`, Flyway migrace V1..V2
- **Příjem:** konzumuje 3 Kafka topiky — `openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events`
- **Outbox:** žádný — služba je read-model a **nepublikuje** doménové události
- **Idempotence:** neaplikuje se — všechny REST endpointy jsou read-only GETy; projekce událostí je upsert podle `party_id` (přirozeně idempotentní)
- **Auth:** Keycloak OIDC (realm `openbank`, klient `openbank-services`). Autorizace dle rolí (`@Authorize`/OPA enforce) je cíl dle ADR-0068 a v současné REST vrstvě **ještě není zadrátována** (viz [03 — API](./03-api.md))
