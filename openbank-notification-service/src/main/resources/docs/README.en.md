# openbank-notification-service — Documentation

> **What it is:** the customer notification service — it consumes notification requests from Kafka, renders reviewed copy for EMAIL, PUSH or authenticated INBOX, and records the outcome. Email and push record provider acceptance separately from inbox visibility. Originating domain services decide when contact is warranted.

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

- **Tech stack:** Kotlin / Quarkus / PostgreSQL / Hibernate Reactive / Kafka / Quarkus Mailer / OIDC. Release version comes from `version.txt`.
- **Port:** 8112 (app), 8085 (management — health, metrics, docs). *Note:* `openapi.yaml`'s `servers[0]` still lists `8125` — that is a stale spec example, the running port is 8112.
- **Persistence:** PostgreSQL database `openbank_notifications`, public schema, Flyway migrations. V18 adds immutable managed template revisions and inbox visibility time.
- **Inbound:** Kafka topic `openbank.notification.requests` (consumer group `notification-service`), `NotificationRequest` JSON payloads.
- **Outbox:** `notification_outbox` records outcomes; an INBOX row and its `VISIBLE` outcome commit together. `VISIBLE` means readable in the authenticated feed, not opened by the customer.
- **Templates:** staff draft and publish locale/channel copy at `/api/v1/notification-templates`; a different operator publishes. Identity and permitted variables stay in code. Each notification pins the published revision, with a reviewed built-in fallback. The editor lives in Communication Studio.
- **Push:** FCM / APNs adapters may be disabled; a skipped adapter records `SUPPRESSED`, never provider acceptance. Lock-screen text stays generic.
- **Idempotency:** a supplied deduplication key is enforced by the notifications table; replay of the same fact does not create a second inbox item.
- **Auth:** Keycloak OIDC. Read APIs require `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`; dispatch-control (break-glass) requires `ROLE_OPERATOR`/`ROLE_ADMIN` with four-eyes on resume.
