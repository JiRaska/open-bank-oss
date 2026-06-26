# openbank-api-gateway — Documentation

> **What it is:** a thin **north-south API gateway** (Kong OSS, DB-less) that fronts the OpenBank dockerized stack with one public proxy endpoint and explicit upstream routing per service. **What it is NOT:** a business service — it owns no domain, no database, no Kafka topic and no outbox; it does not validate tokens itself (auth is *passthrough* — downstream Quarkus services keep validating Keycloak OIDC).

This documentation is published for the Docs-as-Service pattern (see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Unlike the Quarkus services, the gateway is a Kong container and has no `/q/openbank/docs` management endpoint of its own; these files are the source of truth for the admin UI Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the gateway does, who calls it, where it sits in the topology |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | Kong DB-less config, routing model, auth passthrough |
| [03 — API](./03-api.md) | Service developers, integrators | Proxy/admin surface, route table, error model, versioning |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Why there is no datastore; declarative config as the only state |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, FinOps tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, NIS2 mapping for an edge component |

## TL;DR

- **Tech stack:** Kong OSS `3.7.1`, DB-less (declarative) mode, Docker Compose for local run
- **Ports:** `8000` (proxy), `8001` (admin API) — overridable via `KONG_PROXY_PORT` / `KONG_ADMIN_PORT`
- **Persistence:** **none** — `KONG_DATABASE=off`; the only state is `kong/kong.yml` (`_format_version: "3.0"`)
- **Outbox / Kafka:** N/A — the gateway emits no domain events
- **Idempotency:** N/A at the gateway; idempotency is owned by downstream services (`Idempotency-Key`)
- **Auth:** **passthrough** (default) — forwards `Authorization`, `X-Request-Id`, `X-Correlation-Id` unchanged; downstream services validate the Keycloak token. Optional Kong OSS `jwt` plugin placeholders live in `.env.example`.
- **Routed services:** 14 upstreams on `host.docker.internal:8100–8117` (account, ledger, transaction, balance, consent, psd2, agent, party, notification, audit, kyc, sepa, domestic, aml)
