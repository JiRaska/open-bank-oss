---
date: 2026-05-26
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kafka, architecture, libs]
summary: "Every service publishing Kafka events writes them to an outbox table in the same DB transaction and lets a separate dispatcher publish them; direct kafkaTemplate.send from request handling is forbidden."
---

# 3. Transactional outbox for Kafka event publishing

> **Amended (2026-07-16) — dispatch mechanism is the libs poller, not Debezium CDC.**
> This ADR originally named Debezium CDC (WAL tailing) as the dispatch mechanism. The
> implementation never went that way: dispatch is an application-level poller in
> `openbank-libs` ([ADR-0013](0013-shared-outbox-in-openbank-libs.md)), hardened to
> regulatory grade by [ADR-0050](0050-regulatory-grade-outbox-dispatch.md), whose D5
> affirms the poller as the implementation of record and orders this note. Debezium
> remains in the platform only for the ClickHouse analytics feed, not for the outbox.
>
> **The invariants this ADR asserts are unchanged and still binding** — write-in-same-
> transaction, idempotency by `event.id`, `headers` propagation, per-aggregate ordering,
> no direct `kafkaTemplate.send(...)` from request-handling code. Only the *mechanism*
> that moves a row from outbox to Kafka is different. Read this ADR for the invariants;
> read ADR-0013/0050 for how dispatch actually works.

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
2. A separate dispatch process tails the outbox table and publishes events to the corresponding
   Kafka topic. **Mechanism of record: the `openbank-libs` poller** (ADR-0013, hardened by
   ADR-0050) — `AbstractOutboxDispatcher` claims unpublished rows on a scheduled tick with
   `SELECT … FOR UPDATE SKIP LOCKED`, publishes, and advances `attempt_count`. (This ADR
   originally specified Debezium CDC; see the amendment note above.)
3. Once published, the row is marked `published_at = now()`.
4. A janitor job deletes rows older than 7 days where `published_at IS NOT NULL`.

Direct `kafkaTemplate.send(...)` from request-handling code is **forbidden** and is a code-review blocker.

The reference implementation is `openbank-libs-runtime`'s shared outbox primitives
(`persistence/outbox/AbstractOutboxDispatcher.kt`), which every event-publishing service extends.
Note the operational footgun ADR-0050 documents: `openbank.outbox.dispatch-enabled` defaults to
`false`, so a service with an outbox entity that never sets it `true` silently never dispatches —
enforced by `.github/scripts/check-outbox-dispatch-enabled.sh`.

## Alternatives considered

- **Dual write: update the DB and publish to Kafka directly (the status quo)** — call `kafkaTemplate.send(...)` from request-handling code alongside the business transaction. Rejected: the two operations are not atomic, producing lost events, duplicate events on retry after a crash, or ghost events for a transaction that rolled back — each of which the ADR says produces real money inconsistencies. Direct `kafkaTemplate.send(...)` from request handling is a code-review blocker.
- **Distributed XA / 2PC across DB and broker** — make the state change and the publish one distributed transaction. Rejected: operationally heavy and not supported by Kafka.
- **Debezium CDC (WAL tailing) as the dispatch mechanism** — tail the database write-ahead log to move outbox rows to Kafka. Originally specified by this ADR, but per the 2026-07-16 amendment the implementation never went that way; the mechanism of record is the `openbank-libs` application-level poller (ADR-0013, hardened by ADR-0050), and Debezium remains only for the ClickHouse analytics feed.

## Consequences

**Positive**
- Atomicity: event publication is guaranteed if and only if business state change committed.
- Idempotency: downstream can deduplicate by `event.id`.
- Auditability: every event lives in the outbox before publication — easy forensics.
- Replayability: failed Kafka pipeline can be replayed by re-emitting unpublished outbox rows.

**Negative**
- Adds latency between commit and Kafka publish — bounded by the poller tick interval, not by
  WAL lag as originally assumed.
- Each service runs its own dispatcher: no central Kafka Connect cluster to operate, but the
  dispatch-enabled flag becomes a per-service correctness requirement (see the footgun above).
- Outbox table grows; janitor must run reliably.

**Mitigation**
- Dispatch lag and backlog are exposed as per-service Micrometer gauges (ADR-0050); alerts key
  off backlog and `attempt_count` reaching `MAX_ATTEMPTS`.
- Janitor is a scheduled job with alert on backlog > 1M rows.

## Compliance impact

- PCI DSS: not applicable — no cardholder data scoped to the outbox in this ADR.
- DORA:    engaged — atomic publication, replay and dispatch alerting are ICT resilience controls; specific articles not mapped in this ADR.
- GDPR:    not applicable — ADR does not scope personal data in event payloads.
- PSD2:    not applicable — internal event publication, no TPP-facing interface.
- CNB:     engaged — the ADR claims auditability and forensics of every published event; no specific provision cited in this ADR.

## References

- Chris Richardson, "Microservices Patterns" — Transactional Outbox
- [ADR-0013](0013-shared-outbox-in-openbank-libs.md) — shared outbox primitives in `openbank-libs` (dispatch mechanism of record)
- [ADR-0050](0050-regulatory-grade-outbox-dispatch.md) — regulatory-grade outbox dispatch; D5 orders this ADR's amendment
