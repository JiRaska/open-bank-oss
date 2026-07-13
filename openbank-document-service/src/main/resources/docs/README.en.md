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
