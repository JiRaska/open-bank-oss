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

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 (RESTEasy Reactive) / JDK 21+ / Kotlin coroutines — PostgreSQL via **plain JDBC + Flyway**, **no JPA**
- **Port:** 8109 (app HTTP), 8085 (management — health, metrics, docs)
- **Persistence:** PostgreSQL stores the `agent_proposal` HITL approval queue (ADR-0031 D4) and the durable `agent_audit_outbox`; rate-limiter state is **in-memory** only
- **Outbox:** `agent_audit_outbox` acknowledges AI-attributed audit events before Kafka delivery; runtime activation remains controlled pending attestation
- **Idempotency:** N/A — all operations are read-only / non-mutating
- **Auth:** Keycloak OIDC (resource server, realm `openbank`); outbound MCP tool calls carry a `openbank-services` client-credentials Bearer (ADR-0031 / ADR-0034)
- **Governance:** every tool call passes the OPA-backed **AgentPolicyGate** (deny-by-default), bounded by the `ui-assistant` charter in `agents.yaml`; enforcement is `advisory` by default, flip to `block` (ADR-0031 D9)
