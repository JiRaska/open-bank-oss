# openbank-agent-service — Documentation

> **What it is:** the platform's **AI agent layer** — an MCP (Model Context Protocol) server that exposes read-only OpenBank domain operations as AI-callable tools, plus the server-side **admin-UI assistant** (a governed model↔tool reasoning loop). **What it is NOT:** it is **not** a money-path service, it holds **no banking data of its own**, and it **never mutates state** — every tool it can reach is read-only and PII-masked (ADR-0031).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 view, hexagonal layers, model gateway, policy gate |
| [03 — API](./03-api.md) | Service developers, integrators | MCP JSON-RPC contract, `/agent/chat`, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | What it persists (almost nothing), audit/prompt-hash, PII posture |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, FinOps tier, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, AI Act, PSD2/AML read-surface mapping |
| [08 — Workload identity](./08-workload-identity.md) | Engineering, SRE | ADR-0177 Phase 1 pilot: projected SA token, Keycloak token exchange, secret fallback |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 (RESTEasy Reactive) / JDK 21+ / Kotlin coroutines — PostgreSQL via **plain JDBC + Flyway**, **no JPA**
- **Port:** 8109 (app HTTP), 8085 (management — health, metrics, docs)
- **Persistence:** PostgreSQL stores the `agent_proposal` HITL approval queue (ADR-0031 D4), the durable `agent_audit_outbox`, and the phase-3 `agent_merge_record` merge evidence; rate-limiter state is **in-memory** only
- **Outbox:** `agent_audit_outbox` acknowledges AI-attributed audit events before Kafka delivery; runtime activation remains controlled pending attestation
- **Idempotency:** merge-record ingest is idempotent by `prNumber` (`ON CONFLICT DO NOTHING` — a redelivered webhook returns the stored record with `inserted=false`, no second audit row); assistant/tool calls are read-only and non-mutating
- **Auth:** Keycloak OIDC (resource server, realm `openbank`); outbound MCP tool calls carry a `openbank-services` client-credentials Bearer (ADR-0031 / ADR-0034)
- **Governance:** every tool call passes the OPA-backed **AgentPolicyGate** (deny-by-default), bounded by the `ui-assistant` charter in `agents.yaml`; enforcement is `advisory` by default, flip to `block` (ADR-0031 D9)

## Account read-tool consumer contracts

`AgentAccountReadPactConsumerTest` exercises production `RestDownstreamReadAdapter` dispatch for `get_account` and `get_account_by_iban` through an anonymous account client bound to a Pact mock server. Three interactions cover account-by-ID 200, account-by-IBAN 200 and missing-IBAN 404; the last must propagate a client exception instead of invented account data. Assertions retain identifying response fields, not every field of the provider response.

These tests do not invoke the production REST-client annotations or OIDC filters, MCP HTTP routing, model execution, charter/HITL enforcement, audit persistence or live JWT/OPA authorization. Existing synthetic account/unknown-IBAN provider states are reused. Separate provider replay and normal CI remain necessary; local mock success is not runtime delivery proof. The test-only Pact dependency uses the existing version catalog.

`AgentTransactionReadPactConsumerTest` additionally covers `get_transaction`: a seeded by-ID transaction 200 passes through the real read adapter, while an unknown transaction 404 propagates a client exception. It uses the same handwritten-client Pact boundary and shares the existing posted-transaction and no-seed provider states with the Party portability pact. These two interactions do not establish caller authorization or ownership isolation.


## FX and AML read-tool consumer contracts

`AgentFxReadPactConsumerTest` additionally covers `fx_get_rate` (EUR/CZK 200 and unknown USD/JPY 404) and `fx_list_rates` (200 bare-array rate board). `AgentAmlReadPactConsumerTest` covers `aml_get_case` (seeded case 200 and unknown case 404) and `aml_list_cases` (200 with partyId/limit/offset query forwarding). All six interactions call the real `RestDownstreamReadAdapter` with anonymous client implementations bound to Pact mock servers. Their URLs/query construction is handwritten; these contracts do not execute the production MicroProfile REST-client annotations or OIDC filters. Optional FX source queries are not covered.

FX matchers check currency/decimal wire shape, not pricing accuracy or provider-side positive-rate invariants; the board contract does not pin row order or a particular count. AML provider twins seed an idempotent synthetic case and explicitly register the unknown-case state so the folder filter retains its 404 interaction. Staff TestSecurity/OidcSecurity fixtures do not establish the agent service account's JWT, enforced OPA grants, tenant/ownership isolation or cross-party filtering. Provider-state parity, actual replay counts without skips, and normal CI remain required; no live broker, model/MCP request or deployed authorization result is claimed.

### Catalog and Ledger read contracts

The Agent Catalog Pact covers product listing, product detail, fees and an unknown-product 404.
Its three provider states reuse canonical seeded products in both Product Catalog verifier twins.
The Ledger Pact retains its three journal/trial-balance reads and adds an unauthenticated journal
401 using the existing negative-auth provider state and separate anonymous folder verifier.
The consumer fixtures call the real read adapter through handwritten HTTP clients; they do not
execute the production MicroProfile annotations or establish JWT, OPA, ownership or live broker
interoperability. Catalog/Ledger mapping needs its own real-client proof; FX/AML mapping results
cannot be transferred to these interfaces. These supplemental interactions are not yet executed.
