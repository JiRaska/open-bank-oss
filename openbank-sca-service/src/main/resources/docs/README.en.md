# openbank-sca-service — Documentation

> **What it is:** the Strong Customer Authentication (SCA) engine — it initiates and verifies step-up authentication challenges (SMS OTP, TOTP, push, biometric) and records signature-verified, dynamically-linked out-of-band device approvals (ADR-0021). **What it is NOT:** the identity provider (that's Keycloak), the consent store (`openbank-consent-service`), nor the payment authoriser (the payment services *call* SCA, they do not live here).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow, decoupled-approval design |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | PSD2/RTS, DORA, GDPR, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus (LTS) / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache) / Redis (Valkey) / Kafka
- **Port:** 8110 (app), 8085 (management — health, metrics, docs)
- **Persistence:** dedicated PostgreSQL database `openbank_sca`, Flyway migrations V1..V4
- **Outbox:** `sca_outbox` → Kafka topic `openbank.sca.challenge.event`
- **Idempotency:** `Idempotency-Key` (or `X-Request-ID`) header → Redis (300 s TTL), plus a command-derived idempotency key in the use case
- **Auth:** Keycloak OIDC; mutations gated by `@Authorize` (OPA sidecar, ADR-0034) + per-party ownership enforcement
- **Money-path service:** yes (`rules.yaml: money_path_services`) — 2 approvals + threat model required (ADR-0030)
- **Key ADR:** [ADR 0021 — SCA decoupled device approval, never auto-approve](../../../../docs/adr/0021-sca-decoupled-device-approval-no-auto-approve.md)
