# 6. AsyncAPI 3.0 for all Kafka topics

Date: 2026-05-26
Status: Accepted

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

## References

- AsyncAPI 3.0 specification
- Apicurio Schema Registry
- Confluent Schema Registry
