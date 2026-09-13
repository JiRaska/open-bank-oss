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
        │                                                       └────────────────────┘ │
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

### Adapters / infrastructure (`com.openbank.audit.infrastructure`)
- **`persistence.AuditRepository`** + `AuditEntryEntity` — Hibernate Reactive Panache repository. `save` runs inside `Panache.withTransaction`; `findByAggregateId` reads inside `Panache.withSession`, ordered by `occurredAt DESC`, paged to a caller-supplied limit (clamped 1..500).
- **`rest.AuditResource`** — JAX-RS resource exposing the single read endpoint, gated with `@RolesAllowed`.

## Ingest flow (consume)

1. A producer (account, transaction, balance, party, kyc, consent service) emits a domain event to its Kafka topic.
2. `AuditConsumer` receives it on the shared `audit-events-in` channel (consumer group `audit-service`, `auto.offset.reset=earliest`).
3. The payload is parsed; aggregate identity is inferred; a new `AuditEntry` with a fresh `entry_id` UUID and `recordedAt = now` is built.
4. `AuditRepository.save` persists it transactionally. The DB trigger stamps `retention_until = occurred_at + 10 years`; immutability rules block any later mutation.

## No outbound re-emit path

audit-service is a consumer/sink: it writes append-only `audit_entries` from other services'
events and publishes no domain event of its own. It previously carried a full
transactional-outbox pipeline (`audit_outbox` table, `AuditOutboxDispatcher`,
`KafkaAuditOutboxEventPublisher`) intended for a future compliance/SIEM re-emit, but nothing
ever wrote to it — deleted as dead code (#5126), mirroring PR #1364's removal of sepa-instant's
analogous unused `SctInstOutboxPort` pipeline. If a real need for an outbound audit event
emerges, design it against that consumer, not by resurrecting this apparatus speculatively.

## Key ports

| Port | Direction | Implementation |
|---|---|---|
| `audit-events-in` (Kafka channel) | inbound | `AuditConsumer.consume` |
| `AuditRepository.save` / `findByAggregateId` | persistence | Panache + PostgreSQL |
| `GET /api/v1/audit/entries/{aggregateId}` | inbound (HTTP) | `AuditResource` |

## Cross-cutting

- **Reactive everywhere** — Hibernate Reactive + Mutiny, bridged to Kotlin coroutines via `awaitSuspending`.
- **Observability** — Micrometer/Prometheus metrics and OpenTelemetry tracing (OTLP exporter), SmallRye Health probes.
- **Security headers** — strict set configured globally (CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff, referrer/permissions policy).
