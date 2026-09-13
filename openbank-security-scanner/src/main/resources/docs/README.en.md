# openbank-security-scanner — Documentation

> **What it is:** the fleet-wide security health scanner for the OpenBank platform. It probes all 27 microservices every 30 minutes against the OWASP Top 10 2021 checklist, produces a scored `PlatformSecurityReport`, and manages DORA-grade ICT incident lifecycle. **What it is NOT:** a WAF (Web Application Firewall), a SIEM, nor a penetration testing tool — it performs black-box HTTP-level checks only.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, scan pipeline, event emission |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, scan results model, ICT incident API |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Migrations, and why nothing operational is persisted |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA Art. 17 incident reporting, EBA ICT, OWASP mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL (schema `openbank_security`, Flyway history only — see [04 — Data](./04-data.md))
- **Port:** 8120 (app)
- **Auth:** OIDC disabled (internal platform service, no external exposure)
- **Scheduler:** probes all 27 fleet services every 30 minutes (`@Scheduled(every = "30m")`)
- **Scan checks:** OWASP Top 10 2021, security headers, CORS, unauthenticated actuators, OpenAPI exposure
- **State:** none persisted — scan results and ICT incidents live in in-memory maps and are lost on pod restart
- **Events:** one topic, `openbank.security.ict.incident`, emitted directly to Kafka (no outbox; the unused one was removed in #4709)
- **ICT incidents:** DORA Art. 17 lifecycle (OPEN → INVESTIGATING → CONTAINED → RESOLVED → CLOSED)
- **Scoring:** 0–100 per service, letter grade A+ / A / B / C / D / F
