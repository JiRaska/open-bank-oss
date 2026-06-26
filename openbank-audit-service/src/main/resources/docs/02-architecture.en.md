# Architecture

The service follows the hexagonal (ports & adapters) layout mandated by [ADR-0002](../../../../docs/adr/0002-hexagonal-architecture.md): the domain has zero framework imports; everything that touches Kafka, the database or HTTP lives in adapters.

## C4 — container view

```
        ┌───────────────────────── openbank-audit-service ──────────────────────────┐
        │                                                                            │
 Kafka  │  ┌─────────────────┐      ┌──────────────────┐      ┌────────────────────┐ │
 topics ─┼─►│ AuditConsumer   │────► │ AuditRepository  │────► │ PostgreSQL         │ │
 (in)   │  │ (@Incoming)     │ save │ (Panache)        │ JDBC │ openbank_audit     │ │
        │  └─────────────────┘      └──────────────────┘      │  • audit_entries   │ │
        │                                                       │  • audit_outbox    │ │
        │  ┌─────────────────┐      ┌──────────────────┐      └────────────────────┘ │
 Kafka  │  │ AuditOutbox     │◄──── │ AuditOutbox      │                              │
 (out)  │◄─┤ Dispatcher      │ drain│ Repository       │                              │
        │  │ (@Scheduled 5s) │      └──────────────────┘                              │
        │  └─────────────────┘                                                        │
        │                                                                            │
        │  ┌─────────────────┐                                                        │
 HTTP  ─┼─►│ AuditResource   │────► AuditRepository.findByAggregateId(...)            │
 (read) │  │ (JAX-RS, OIDC)  │                                                        │
        │  └─────────────────┘                                                        │
        └────────────────────────────────────────────────────────────────────────────┘
```

## Hexagonal layers

### Domain (`com.openbank.audit.domain.model`)
- **`AuditEntry`** — the core aggregate: an immutable Kotlin `data class` describing one recorded event (id, eventType, aggregateType, aggregateId, actor, payload, sourceService, correlationId, occurredAt, recordedAt). No framework imports.

### Application (`com.openbank.audit.application`)
- **`AuditConsumer`** — the inbound use case. `@Incoming("audit-events-in")` receives a raw JSON string, parses it with Jackson, maps it to an `AuditEntry` (deriving `aggregateType`/`aggregateId` from the payload shape), and calls `AuditRepository.save`. Failures are logged and swallowed so a single poison message never stalls the consumer.
- **`application.port.out`** — outbound ports: `AuditOutboxRepository` (read processable rows, mark sent/failed) and `AuditOutboxEventPublisher` (publish a payload). `AuditOutboxStatus` enum: `PENDING` / `SENT` / `FAILED`.

### Adapters / infrastructure (`com.openbank.audit.infrastructure`)
- **`persistence.AuditRepository`** + `AuditEntryEntity` — Hibernate Reactive Panache repository. `save` runs inside `Panache.withTransaction`; `findByAggregateId` reads inside `Panache.withSession`, ordered by `occurredAt DESC`, paged to a caller-supplied limit (clamped 1..500).
- **`persistence.entity.AuditOutboxEntity`** + `repository.AuditOutboxRepositoryImpl` — the transactional-outbox row mapping for `audit_outbox`.
- **`outbox.AuditOutboxDispatcher`** — `@Scheduled(every = "5s")` drains up to 25 processable rows, publishing each through a resilience-wrapped call (`@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`) and marking it `SENT` or `FAILED`. The scheduler catches and swallows top-level errors so it never crashes the loop.
- **`kafka.KafkaAuditOutboxEventPublisher`** — implements `AuditOutboxEventPublisher` by emitting a `Record<String,String>` on the `audit-events-out` channel.
- **`rest.AuditResource`** — JAX-RS resource exposing the single read endpoint, gated with `@RolesAllowed`.

## Ingest flow (consume)

1. A producer (account, transaction, balance, party, kyc, consent service) emits a domain event to its Kafka topic.
2. `AuditConsumer` receives it on the shared `audit-events-in` channel (consumer group `audit-service`, `auto.offset.reset=earliest`).
3. The payload is parsed; aggregate identity is inferred; a new `AuditEntry` with a fresh `entry_id` UUID and `recordedAt = now` is built.
4. `AuditRepository.save` persists it transactionally. The DB trigger stamps `retention_until = occurred_at + 10 years`; immutability rules block any later mutation.

## Outbox → Kafka flow (re-emit)

The service carries the standard transactional-outbox machinery (`audit_outbox` table, `AuditOutboxDispatcher`, `KafkaAuditOutboxEventPublisher`) so recorded events can be re-published downstream (e.g. to a compliance/SIEM stream) with at-least-once delivery and back-pressure-safe resilience.

> **Operational note:** the inbound channel `audit-events-in` is configured in `application.yaml`; the outbound `audit-events-out` channel is referenced by the publisher in code but is **not yet declared** in `application.yaml`. Treat the re-emit path as wired-but-dormant until the outgoing connector is configured (see [05 — Operations](./05-operations.md)). This is a deliberate inventory note, not a fabricated topic.

## Key ports

| Port | Direction | Implementation |
|---|---|---|
| `audit-events-in` (Kafka channel) | inbound | `AuditConsumer.consume` |
| `AuditRepository.save` / `findByAggregateId` | persistence | Panache + PostgreSQL |
| `AuditOutboxRepository` | outbound (DB) | `AuditOutboxRepositoryImpl` |
| `AuditOutboxEventPublisher` | outbound (Kafka) | `KafkaAuditOutboxEventPublisher` |
| `GET /api/v1/audit/entries/{aggregateId}` | inbound (HTTP) | `AuditResource` |

## Cross-cutting

- **Reactive everywhere** — Hibernate Reactive + Mutiny, bridged to Kotlin coroutines via `awaitSuspending`.
- **Observability** — Micrometer/Prometheus metrics and OpenTelemetry tracing (OTLP exporter), SmallRye Health probes.
- **Security headers** — strict set configured globally (CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff, referrer/permissions policy).
