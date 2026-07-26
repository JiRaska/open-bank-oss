# openbank-notification-service — Documentation

> **What it is:** the outbound customer-communication service — it consumes notification *requests* from Kafka, renders a template per channel (EMAIL / SMS / PUSH / IN_APP), persists the notification record and delivers it (email via SMTP, push via FCM/APNs). **What it is NOT:** it does not decide *when* a customer should be notified (the originating domain services do — account, transaction, kyc, consent), it is not on the money path, and it does not store balances, payments or ledger entries.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, consume + outbox + push flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, dispatch-control four-eyes, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO, serverless tier |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / PostgreSQL 16 / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka) / Quarkus Mailer / OIDC. Service release version `0.4.0`.
- **Port:** 8112 (app), 8085 (management — health, metrics, docs). *Note:* `openapi.yaml`'s `servers[0]` still lists `8125` — that is a stale spec example, the running port is 8112.
- **Persistence:** PostgreSQL database `openbank_notifications`, public schema, Flyway migrations V1..V6.
- **Inbound:** Kafka topic `openbank.notification.requests` (consumer group `notification-service`), `NotificationRequest` JSON payloads.
- **Outbox:** table `notification_outbox` → dispatcher channel `notification-events-out` (a generic outbox-relay; the outgoing Kafka topic binding is **TBD** — not yet wired in `application.yaml`).
- **Push:** FCM / APNs adapters, **off by default** (Vault-injected credentials); a disabled adapter records a successful no-op. PUSH fans out to every ACTIVE device token registered for the party.
- **Idempotency:** none on the inbound path by design — delivery is at-least-once and a redelivery re-persists a fresh row (acceptable because no money path).
- **Auth:** Keycloak OIDC. Read APIs require `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`; dispatch-control (break-glass) requires `ROLE_OPERATOR`/`ROLE_ADMIN` with four-eyes on resume.
