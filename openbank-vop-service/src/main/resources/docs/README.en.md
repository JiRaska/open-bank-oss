# openbank-vop-service — Documentation

> **What it is:** the **Verification of Payee** service (ADR-0171) — it answers one question, "is this payee name the name held on this IBAN?", with `match` / `close_match` / `no_match` / `no_data`, so the payer can be told before they authorise a credit transfer (Regulation (EU) 2024/886 Art. 5c, in force since 2025-10-09). **What it is NOT:** it does **not** move money, it does **not** block a payment (it *informs*; the payer decides), it is **not** a fraud engine (→ `openbank-fraud-service`), it is **not** the sanctions gate (→ `openbank-sanctions-service`, which fails *closed* where this fails *open*), and it does **not** implement the fraud-reimbursement liability shift (IPR Art. 5d — explicitly out of scope).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, why it exists, what it deliberately does not do |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | Hexagonal layers, the two-hop name lookup, the fail-open decision |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, the four outcomes, the disclosure rule |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, why it stores hashes, retention |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO, rate limit |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | IPR Art. 5c, GDPR, DORA, what is NOT covered |

## TL;DR

- **Tech stack:** Kotlin / Quarkus (RESTEasy Reactive) / JDK 25 / PostgreSQL / Hibernate Reactive (Panache) / Valkey — reactive (`Uni`), not suspend. No Kafka: VoP publishes no events.
- **Port:** 8149 (app), 8086 (management — `/q` root path).
- **Persistence:** PostgreSQL database `openbank_vop`, Flyway `V1` (one table, `vop_verification`). CNPG `instances: 2` (ADR-0159).
- **Idempotency:** none, and none is needed — `POST /vop/verify` is a read dressed as a POST (the IBAN and name are personal data and must not reach a URL). It mutates nothing but the evidence log.
- **Auth:** Keycloak OIDC (RS256 JWT) + OPA `vop.verify`. Reads allow `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS`; M2M callers are admitted by the `service-account-*` convention.
- **Rate limit:** 60/min per requester, Valkey-backed, **fails closed**. This is a security control, not throughput management — see §05.
- **Money-path:** **Yes** — `rules.yaml: money_path_services`. It sits on the pre-execution path of every euro credit transfer, so it needs 2 approvals and a [threat model](../../../../docs/threat-models/openbank-vop-service.md).

## The three things to know before reading the code

1. **VoP fails OPEN — deliberately the inverse of its neighbour.** The sanctions gate (ADR-0032) *holds* a payment when screening is unavailable, because a sanctions miss is a legal breach. VoP must not: IPR Art. 5c requires the PSP to **warn**, and refusing every payment during a VoP outage would itself breach the execution-time obligation the same regulation imposes. The two gates sit side by side in the same flow with opposite failure semantics **by design**. Do not "fix" this for consistency.

2. **`no_match` never echoes a name; `close_match` may.** VoP is, by construction, an oracle over account-holder names — that is exactly the function the regulation mandates. Authorization cannot bound it (a payer must be able to check a payee they do not own), so the controls are the **rate limit** plus this **disclosure asymmetry**, enforced in `VopVerification`'s `init` block rather than trusted to callers.

3. **The requester side is honest, not finished.** There is no EPC VoP scheme link here and there will not be one in a reference implementation — the same way the rails reach only `openbank-clearing-simulator`. An external IBAN returns `no_data` / `NO_SCHEME_CONNECTIVITY` through a real seam (`VopSchemeRoutingPort`), rather than a fabricated verdict.
