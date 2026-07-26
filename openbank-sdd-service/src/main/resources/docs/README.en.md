# openbank-sdd-service — Documentation

> **What it is:** the debtor-side **SEPA Direct Debit** service (ADR-0036) — a mandate vault keyed by the rulebook pair `(creditorIdentifier, UMR)`, the mandate lifecycle, a fail-closed collection-authorisation policy and a refund-window assessor. **What it is NOT:** it does **not** move money (no debit, no refund posting — that is delegated to the ledger/payment path), it is **not** the creditor-side issuing service, and it is **not** the CZ domestic *souhlas/povolení k inkasu* (CERTIS) instrument.

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, error model, idempotency |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | PSD2, GDPR, DORA, AML, EPC rulebook mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus (RESTEasy Reactive) / JDK 25 / PostgreSQL / Hibernate Reactive (Panache) / SmallRye Kafka — reactive (`Uni`), not suspend.
- **Port:** 8129 (app), 8086 (management — `/q` root path).
- **Persistence:** PostgreSQL database `openbank_sdd`, Flyway migration `V1` (tables `sdd_mandate`, `sdd_outbox`).
- **Outbox:** `sdd_outbox` → Kafka topic `openbank.sdd.event` (channel `sdd-events-out`).
- **Idempotency:** natural-key idempotent — re-registering the same `(creditorIdentifier, UMR)` returns the stored mandate. No `Idempotency-Key` header / Redis.
- **Auth:** Keycloak OIDC (RS256 JWT); mutations require `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS`/`ROLE_API`, reads additionally allow `ROLE_VIEWER`.
- **Money-path:** **No** — `openbank-sdd-service` is not in `rules.yaml: money_path_services`; v1 never executes an irreversible posting.
