# openbank-notification-service — Dokumentace

> **Co to je:** služba pro odchozí komunikaci se zákazníkem — z Kafky konzumuje *požadavky* na notifikaci, vyrenderuje šablonu pro daný kanál (EMAIL / SMS / PUSH / IN_APP), uloží záznam notifikace a doručí ji (e-mail přes SMTP, push přes FCM/APNs). **Co to NENÍ:** nerozhoduje, *kdy* má být zákazník informován (to dělají původní doménové služby — account, transaction, kyc, consent), není na peněžní cestě a neuchovává zůstatky, platby ani účetní zápisy.

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

- **Tech stack:** Kotlin / Quarkus 3.x / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka) / Quarkus Mailer / OIDC. Release verze služby `0.4.0`.
- **Port:** 8112 (aplikace), 8085 (management — health, metriky, docs). *Pozn.:* `servers[0]` v `openapi.yaml` stále uvádí `8125` — to je zastaralý příklad ve specifikaci, běžící port je 8112.
- **Perzistence:** PostgreSQL databáze `openbank_notifications`, schéma public, Flyway migrace V1..V6.
- **Vstup:** Kafka topic `openbank.notification.requests` (consumer group `notification-service`), payload `NotificationRequest` v JSON.
- **Outbox:** tabulka `notification_outbox` → kanál dispatcheru `notification-events-out` (obecné outbox-relay; navázání odchozího Kafka topicu je **TBD** — zatím není v `application.yaml`).
- **Push:** adaptéry FCM / APNs, **ve výchozím stavu vypnuté** (přihlašovací údaje injektované z Vaultu); vypnutý adaptér zaznamená úspěšný no-op. PUSH se rozesílá na každý ACTIVE device token registrovaný pro party.
- **Idempotence:** na vstupní cestě záměrně žádná — doručení je at-least-once a redelivery uloží nový řádek (přijatelné, protože nejde o peněžní cestu).
- **Auth:** Keycloak OIDC. Čtecí API vyžadují `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`; řízení výpravy (break-glass) vyžaduje `ROLE_OPERATOR`/`ROLE_ADMIN` s four-eyes při resume.
