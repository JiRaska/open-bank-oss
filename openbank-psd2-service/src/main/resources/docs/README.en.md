# openbank-psd2-service — Documentation

> **What it is:** the PSD2 / Open Banking facade for the OpenBank platform — an Account Information Service (AIS) and Payment Initiation Service (PIS) for licensed Third Party Providers (TPPs), plus consent lifecycle and a developer sandbox. **What it is NOT:** the system of record for accounts (`openbank-account-service`), the consent store (`openbank-consent-service`), the payment executors (`openbank-sepa-payment` / `-instant` / `-domestic-payment`), nor the TPP register (`openbank-tpp-registry-service`). It is a stateless translation/orchestration layer in front of those.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow, resilience |
| [03 — API](./03-api.md) | TPP integrators, service developers | Open Banking REST contract, idempotency, eIDAS auth, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Outbox schema, migrations, retention, PII handling |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, serverless tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | PSD2, GDPR, DORA, AML, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / JDK 25 / Hibernate Reactive (Panache) — only an outbox table; the service is otherwise stateless.
- **Port:** 8107 (app), 8085 (management, root-path `/q`). *(Note: `openapi.yaml` currently lists `8122` as the local server — see [03 — API](./03-api.md).)*
- **Persistence:** a single transactional-outbox table `psd2_outbox`; no domain data is persisted here (consents live in `consent-service`, accounts in `account-service`).
- **Outbox:** `psd2_outbox` → Kafka topic `openbank.psd2.events` (dispatcher polls every 5 s).
- **Idempotency:** `Idempotency-Key` header on PIS (payments), `X-Request-ID` on consent creation → Redis (Valkey), 24 h TTL.
- **Auth:** TPP authentication via eIDAS QWAC (`SSL-CLIENT-S-DN`) or `X-TPP-ID`, validated against `tpp-registry-service` for the `AISP` / `PISP` role. Per-resource access is enforced by `consent-service`. (Keycloak OIDC is wired but does not gate the Open Banking paths.)
- **Mode:** sandbox mode enabled by default (`openbank.psd2.sandbox-mode=true`); downstream clients are currently stub implementations.
