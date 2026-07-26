---
date: 2026-05-26
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kafka, api-contract, governance]
summary: "Every Kafka topic is documented in AsyncAPI 3.0 under openbank-contracts, with a Schema Registry enforcing message schemas at runtime and CI blocking release on missing or stale specs."
---

# 6. AsyncAPI 3.0 for all Kafka topics

**Delivery note (updated 2026-07-26):**
- **CI linting gate** — ✅ Shipped: AsyncAPI specs linted in CI; missing or malformed specs block release.
- **Per-service specs** (`openbank-contracts/<service>/asyncapi.yaml`) — ⬜ Pending: specs exist for documented topics; full fleet coverage is the outstanding work.
- **Schema Registry** (Apicurio or Confluent) — 🟡 Partial. **Deployed**, not yet enforcing.
  Apicurio runs in-cluster (`openbank-infra/gitops/apps/apicurio.yaml` plus
  `components/apicurio/{namespace,registry,postgres,network-policies}.yaml`), and
  `openbank-analytics-sink` reads it via `ApicurioSchemaCatalogSource` (unit test + IT).
  What is NOT delivered is the part this ADR actually decides: **runtime enforcement**.
  No topic has a registered message schema — there are zero `.avsc` and zero registered
  JSON Schemas fleet-wide — so no producer fails fast on a schema violation, and the
  registry currently holds no contract to enforce. Compatibility is still signalled only
  by `check-event-schema-compat.py` (a Kotlin constructor diff) and the ADR-0063
  consumer-driven contract tests.
  *(Corrected 2026-07-26: this line previously read "not yet deployed", which was false
  and made the remaining work look larger and differently shaped than it is — the gap is
  registration + enforcement, not provisioning.)*
- **Consumer SDK generation** from specs — ⬜ Pending.

**Open question, not yet decided by this ADR.** The Decision below says message schemas
may be "Avro or JSON Schema" and deliberately does not pick one. Choosing between them —
and choosing how an existing live topic migrates from hand-built JSON to a registered,
enforced schema without breaking in-flight consumers on a rolling deploy — is a decision
this ADR still owes, not an implementation detail. It should be settled here (or in a
successor ADR) before the first schema is registered, because the first one registered
sets the fleet pattern. Tracked in #1916.

## Context

Kafka topics in a microservices system become an implicit API — services produce and consume events without a formal contract. The result:

- Schema changes break downstream consumers without warning.
- New consumers cannot discover what events exist or what they mean.
- Auditors cannot trace which service emits which event for what reason.
- No equivalent of OpenAPI for browsing / generating clients.

AsyncAPI is the de facto standard for documenting event-driven systems, analogous to OpenAPI for REST.

## Decision

Every Kafka topic in OpenBank MUST be documented in AsyncAPI 3.0 format:

- Specs live in `openbank-contracts/<service>/asyncapi.yaml`.
- The spec declares: channel (topic name), message schemas (Avro or JSON Schema), producer and consumer services, retention, partitioning key.
- Schema Registry (Apicurio or Confluent) enforces the message schema at runtime; producers fail-fast on schema violation.
- Schema evolution rules: backward-compatible by default; breaking changes require a new topic version (`*-v2-events-out`) and parallel running until migration complete.
- AsyncAPI specs are linted in CI; missing or stale specs block release.

## Alternatives considered

- **Undocumented Kafka topics as an implicit API (the status quo)** — let services produce and consume events with no formal contract. Rejected: schema changes break downstream consumers without warning, new consumers cannot discover what events exist or what they mean, auditors cannot trace which service emits which event for what reason, and there is no equivalent of OpenAPI for browsing or generating clients.
- **Breaking a topic's schema in place** — evolve an existing topic incompatibly. Rejected: evolution is backward-compatible by default, and a breaking change requires a new topic version (`*-v2-events-out`) with parallel running until migration is complete.

## Consequences

**Positive**
- Event contracts are discoverable.
- Consumer SDKs can be generated.
- Audit trail: every topic has a documented purpose, producer, retention.
- Schema enforcement prevents production-time deserialisation failures.

**Negative**
- Adds documentation burden.
- Schema Registry is operational surface area.

**Mitigation**
- Schema Registry is mature; managed offerings widely available.
- CI scaffolding generates spec skeletons from message classes when missing.

## Compliance impact

- PCI DSS: not applicable — event-contract documentation, no cardholder data in scope.
- DORA:    not applicable — documentation and schema governance, no resilience control claimed.
- GDPR:    not applicable — ADR does not scope personal data in event payloads.
- PSD2:    not applicable — internal Kafka topics, no TPP-facing interface decided.
- CNB:     engaged — the ADR requires every topic have a documented purpose, producer and retention so auditors can trace event emission; no specific provision cited in this ADR.

## References

- AsyncAPI 3.0 specification
- Apicurio Schema Registry
- Confluent Schema Registry
