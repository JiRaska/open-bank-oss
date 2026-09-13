# openbank-security-scanner — Dokumentace

> **Co to je:** fleet-wide bezpečnostní health scanner pro platformu OpenBank. Prověřuje všech 27 mikroslužeb každých 30 minut oproti checklistu OWASP Top 10 2021, produkuje ohodnocený `PlatformSecurityReport` a spravuje DORA-grade životní cyklus ICT incidentů. **Co to NENÍ:** WAF (Web Application Firewall), SIEM, ani nástroj pro penetrační testování — provádí pouze black-box HTTP-level kontroly.

Tato dokumentace je vystavena přímo službou na management endpointu `/q/openbank/docs` (Docs-as-Service pattern — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji fetchuje při zobrazení Service Docs.

## Obsah

| Sekce | Pro koho | Co tam najdeš |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagramy, scan pipeline, vysílání eventů |
| [03 — API](./03-api.md) | Service developers, integrátoři | REST kontrakt, model výsledků skenů, ICT incident API |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Migrace a proč se nic provozního neukládá |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA čl. 17 reportování incidentů, EBA ICT, OWASP mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL (schema `openbank_security`, pouze Flyway historie — viz [04 — Data](./04-data.md))
- **Port:** 8120 (app)
- **Auth:** OIDC vypnuto (interní platformová služba, žádná externí expozice)
- **Scheduler:** prověřuje všech 27 fleet služeb každých 30 minut (`@Scheduled(every = "30m")`)
- **Prověřované kontroly:** OWASP Top 10 2021, security headers, CORS, neautentizované aktuátory, expozice OpenAPI
- **Stav:** nic se neukládá — výsledky skenů i ICT incidenty žijí v in-memory mapách a zanikají s restartem podu
- **Eventy:** jediný topic `openbank.security.ict.incident`, vysílaný přímo do Kafky (žádný outbox; nepoužívaný byl odstraněn v #4709)
- **ICT incidenty:** DORA čl. 17 životní cyklus (OPEN → INVESTIGATING → CONTAINED → RESOLVED → CLOSED)
- **Skórování:** 0–100 na službu, písmenkový grade A+ / A / B / C / D / F
