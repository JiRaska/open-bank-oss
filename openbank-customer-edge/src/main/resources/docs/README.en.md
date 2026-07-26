# openbank-customer-edge — Documentation

> **What it is:** the single internet-facing entry point (BFF + gateway) for the retail customer app — it validates customer JWTs from the `openbank-customers` Keycloak realm, enforces per-party ownership, and proxies an explicit allow-list of routes to backend services ([ADR 0065](../../../../docs/adr/0065-customer-facing-edge-and-keycloak-realm.md)). **What it is NOT:** the operator admin BFF (that's the admin-UI relay, ADR-0056), and it holds **no business state of its own** — it owns no database, no outbox, and no domain aggregate.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, proxy + token flow |
| [03 — API](./03-api.md) | App developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Why there is no datastore, what transits the edge, PII handling |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, FinOps tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 (Eclipse Temurin) / RESTEasy Reactive — **no database**, Redis only
- **Port:** 8128 (app), 8085 (management)
- **Persistence:** owns no database — stateless proxy in that sense (`primaryDatastore: Redis`, `ownsNoDatabase: true`, no `databaseName`, ADR-0071 governance.yaml). It does keep two Redis stores: pending onboardings keyed by `caseId` with a 30-day TTL (ADR-0072) and WebAuthn passkeys keyed by credential id, which are durable (no TTL, ADR-0066 F2)
- **Outbox:** none — the edge emits no domain events; downstream services own their own outboxes
- **Idempotency:** the caller's `Idempotency-Key` is forwarded to upstreams that require it (payments); the edge does not store keys itself
- **Auth (inbound):** Keycloak OIDC, realm `openbank-customers`, role `ROLE_CUSTOMER` required for all routes except `POST /onboarding/start` (anonymous)
- **Auth (outbound):** the customer token is **not** forwarded; the edge fetches its own M2M service-account token (operator `openbank` realm, `client_credentials`) and passes the caller's party via the `X-Customer-Party-Id` header
