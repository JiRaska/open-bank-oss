# 3. Transactional outbox for Kafka event publishing

Date: 2026-05-26
Status: Accepted

## Context

A common failure mode in event-driven systems: a service updates its database AND publishes a Kafka event, and the two operations are not atomic.

- DB commit succeeds, Kafka publish fails → event lost; downstream inconsistent.
- DB commit succeeds, Kafka publish succeeds, but process crashes before commit returns → caller retries; duplicate event.
- DB rolls back, Kafka publish already sent → ghost event published for a transaction that never happened.

In a banking platform, every one of these scenarios produces real money inconsistencies.

Distributed XA / 2PC is operationally heavy and not supported by Kafka. The industry-standard alternative is the **transactional outbox pattern**.

## Decision

Every OpenBank service that publishes Kafka events MUST use the transactional outbox pattern:

1. The service writes the event to an `outbox` table in the **same DB transaction** as the business state change. The table has at minimum: `id UUID PRIMARY KEY`, `aggregate_id`, `aggregate_type`, `event_type`, `payload JSONB`, `headers JSONB`, `created_at TIMESTAMPTZ`, `published_at TIMESTAMPTZ NULL`.
2. A separate process (Debezium CDC reading Postgres WAL) tails the outbox table and publishes events to the corresponding Kafka topic.
3. Once published, the row is marked `published_at = now()`.
4. A janitor job deletes rows older than 7 days where `published_at IS NOT NULL`.

Direct `kafkaTemplate.send(...)` from request-handling code is **forbidden** and is a code-review blocker.

The currently-existing implementation in `openbank-sepa-payment` is the reference. M2 milestone extends this to every event-publishing service.

## Consequences

**Positive**
- Atomicity: event publication is guaranteed if and only if business state change committed.
- Idempotency: downstream can deduplicate by `event.id`.
- Auditability: every event lives in the outbox before publication — easy forensics.
- Replayability: failed Kafka pipeline can be replayed by re-emitting unpublished outbox rows.

**Negative**
- Adds latency between commit and Kafka publish (typically < 1 s with Debezium).
- Requires Debezium operational expertise.
- Outbox table grows; janitor must run reliably.

**Mitigation**
- Debezium operates against Postgres WAL; lag is observable via standard Kafka Connect metrics.
- Janitor is a scheduled job with alert on backlog > 1M rows.

## References

- Chris Richardson, "Microservices Patterns" — Transactional Outbox
- Debezium Outbox Event Router documentation
