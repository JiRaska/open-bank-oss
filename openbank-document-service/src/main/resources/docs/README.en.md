# openbank-document-service — Documentation

> **What it is:** the Document Management bounded context — it owns **document templates**, **renders**
> documents from a template + data map, **stores** them in an object store with rich lifecycle/retention
> metadata, and orchestrates **e-signature ceremonies**. **What it is NOT:** it is NOT on the synchronous
> money path (it emits events, it is never a blocking fund-release gate), it does NOT move money, and it
> is NOT the ledger or the balance engine.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs`
(Docs-as-Service pattern — [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | Hexagonal layers, ports/adapters, render + sign flow |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | eIDAS, GDPR, DORA, retention (10y) |

## TL;DR

- **Tech stack:** Kotlin / Quarkus / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache).
- **Port:** 8143 (app), 8088 (management, root-path `/q`).
- **Persistence:** dedicated database `openbank_documents`, Flyway migrations V1..V3, governance
  schema `documents_schema`.
- **Outbox:** `document_outbox` → Kafka topic `openbank.documents.document.event`.
- **Data classification:** `restricted`; retention **10 years**.
- **Money-path:** **No** — event-emitting, never a synchronous fund-release gate. It IS a trust-boundary
  change ⇒ a threat model is required (`docs/threat-models/document-service.md`).
- **Placeholders (behind ports):** template rendering (logic-less `{{token}}`, ADR-0162), PDF rendering
  (stub, ADR-0162), object store (Postgres BYTEA; S3 + WORM is ADR-0161), PAdES sealing (no-op; EU DSS
  is ADR-0007/0162).

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
