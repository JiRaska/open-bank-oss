# openbank-card-processing-service — Documentation

> **What it is:** the **card money path** (ADR-0283). It takes a card authorisation from an acquirer, asks card-issuance for the decision, holds the approved amount, applies clearing presentments against that hold, releases what is never presented, and posts cleared spend to the books on the `CARD` rail through transaction-service. It also hosts the scheme-agnostic capability ports (BIN lookup, merchant data, tokenisation, disputes) with a simulator binding for each. **What it is NOT:** an issuer-processor (no 3-D Secure ACS, no PIN/HSM, no live scheme connection) and not a PAN vault — it accepts, stores and logs **no PAN, CVV or card credential**.

This documentation is published by the service itself at the management endpoint `/q/openbank/docs` (Docs-as-Service — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it for the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does and does not do, callers, dependencies |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, authorisation and clearing flows, scheme ports |
| [03 — API](./03-api.md) | Integrators, service developers | REST contract, idempotency, refusal model |
| [04 — Data](./04-data.md) | Data, DBA | Schema, invariants, migrations, retention |
| [05 — Operations](./05-operations.md) | DevOps, SRE | Build, config, metrics, schedulers, runbooks |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | PCI DSS scope, GDPR, PSD2, DORA mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / PostgreSQL / Hibernate Reactive (Panache), via the shared `openbank.quarkus-service` Gradle convention.
- **Ports:** 8157 (app HTTP), 8085 (management — health, metrics, docs).
- **Persistence:** PostgreSQL database `openbank_card_processing`, Flyway migrations V1..V2 (`migrate-at-start: true`).
- **Outbox:** `card_outbox` → Kafka topic `openbank.card.processing.events` (ADR-0050). Events: `card.authorised.v1`, `card.declined.v1`, `card.cleared.v1`, `card.hold_released.v1`.
- **Idempotency:** `Idempotency-Key` header is required on authorisation and clearing; a repeated authorisation key returns the first authorisation (UNIQUE index).
- **Auth:** Keycloak OIDC; every endpoint requires `ROLE_API`, `ROLE_OPERATOR` or `ROLE_ADMIN` plus an OPA `@Authorize` action (advisory while `AUTHZ_ENFORCE=false`). The sandbox acquirer is `ROLE_ADMIN` only and off by default.
- **Scheme bindings:** `simulator` by default. BIN lookup can be switched to `visa` or `mastercard` (sandbox APIs); tokenisation and disputes have **no vendor adapter** — choosing `visa`/`mastercard` answers `NOT_BOUND`.
- **Money-path:** treated as money-path by its threat model (`docs/threat-models/openbank-card-processing-service.md`); at the time of writing it is **not yet listed** in `rules.yaml: money_path_services`.
