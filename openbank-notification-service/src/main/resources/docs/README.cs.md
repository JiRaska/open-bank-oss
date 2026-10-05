# openbank-notification-service — Dokumentace

> **Co to je:** služba zákaznických notifikací — z Kafky přijímá požadavky, používá schválené texty pro EMAIL, PUSH nebo autentizovaný INBOX a ukládá výsledek. Přijetí poskytovatelem a viditelnost v inboxu jsou různé výsledky. O tom, kdy zákazníka kontaktovat, rozhoduje příslušná doménová služba.

Tuto dokumentaci publikuje sama služba na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při zobrazení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýrství, tech leads | C4 diagramy, hexagonální vrstvy, tok consume + outbox + push |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, four-eyes řízení výpravy, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooky, SLO, serverless tier |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, NIS2 mapování |

## TL;DR

- **Tech stack:** Kotlin / Quarkus / PostgreSQL / Hibernate Reactive / Kafka / Quarkus Mailer / OIDC. Verze vydání vychází z `version.txt`.
- **Port:** 8112 (aplikace), 8085 (management — health, metriky, docs). *Pozn.:* `servers[0]` v `openapi.yaml` stále uvádí `8125` — to je zastaralý příklad ve specifikaci, běžící port je 8112.
- **Perzistence:** PostgreSQL databáze `openbank_notifications`, schéma public, Flyway migrace. V18 přidává neměnné revize šablon a čas zviditelnění v inboxu.
- **Vstup:** Kafka topic `openbank.notification.requests` (consumer group `notification-service`), payload `NotificationRequest` v JSON.
- **Outbox:** `notification_outbox` ukládá výsledky; řádek INBOX a událost `VISIBLE` se potvrdí společně. `VISIBLE` znamená dostupnost v autentizovaném přehledu, nikoli přečtení zákazníkem.
- **Šablony:** zaměstnanec připraví text podle jazyka a kanálu přes `/api/v1/notification-templates`, jiný zaměstnanec jej publikuje. Identita a povolené proměnné zůstávají v kódu. Notifikace uchová použitou revizi; při nedostupnosti úložiště slouží schválený vestavěný text. Editor je v Communication Studio.
- **Push:** vypnutý adaptér zapisuje `SUPPRESSED`, nikoli přijetí poskytovatelem. Text na zamčené obrazovce zůstává obecný.
- **Idempotence:** dodaný deduplikační klíč vynucuje tabulka notifikací; opakování stejné události nevytvoří druhý záznam v inboxu.
- **Auth:** Keycloak OIDC. Čtecí API vyžadují `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`; řízení výpravy (break-glass) vyžaduje `ROLE_OPERATOR`/`ROLE_ADMIN` s four-eyes při resume.
