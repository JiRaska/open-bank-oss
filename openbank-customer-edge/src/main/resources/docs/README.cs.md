# openbank-customer-edge — Dokumentace

> **Co to je:** jediný vstupní bod z internetu (BFF + brána) pro retailovou zákaznickou aplikaci — validuje zákaznické JWT z Keycloak realmu `openbank-customers`, vynucuje vlastnictví podle party a proxuje explicitní allow-list cest na backendové služby ([ADR 0065](../../../../docs/adr/0065-customer-facing-edge-and-keycloak-realm.md)). **Co to NENÍ:** operátorská admin BFF (to je relay admin-UI, ADR-0056) a nedrží **žádný vlastní byznysový stav** — nemá databázi, outbox ani doménový agregát.

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde stojí v doméně |
| [02 — Architektura](./02-architecture.md) | Vývoj, tech leadi | C4 diagramy, hexagonální vrstvy, proxy + tok tokenů |
| [03 — API](./03-api.md) | Vývojáři aplikací, integrátoři | REST kontrakt, idempotence, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Proč není žádné úložiště, co edge tranzituje, zacházení s PII |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, FinOps tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML, NIS2 |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 (Eclipse Temurin) / RESTEasy Reactive — **bez databáze**, pouze Redis
- **Port:** 8128 (aplikace), 8085 (management)
- **Perzistence:** nevlastní žádnou databázi — v tomto smyslu bezstavová proxy (`primaryDatastore: Redis`, `ownsNoDatabase: true`, bez `databaseName`, governance.yaml dle ADR-0071). Drží ale dvě úložiště v Redisu: rozpracované onboardingy klíčované `caseId` s TTL 30 dní (ADR-0072) a WebAuthn passkeys klíčované id credentialu, které jsou trvalé (bez TTL, ADR-0066 F2)
- **Outbox:** žádný — edge nevydává doménové události; downstream služby mají vlastní outboxy
- **Idempotence:** `Idempotency-Key` od volajícího se přeposílá upstreamům, které jej vyžadují (platby); edge sám klíče neukládá
- **Auth (příchozí):** Keycloak OIDC, realm `openbank-customers`, role `ROLE_CUSTOMER` vyžadována na všech cestách kromě `POST /onboarding/start` (anonymní)
- **Auth (odchozí):** zákaznický token se **nepřeposílá**; edge si získá vlastní M2M servisní token (operátorský realm `openbank`, `client_credentials`) a party volajícího předá hlavičkou `X-Customer-Party-Id`
