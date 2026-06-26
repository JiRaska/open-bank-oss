# Shared transactional outbox primitives in openbank-libs

Date: 2026-05-28
Status: Accepted
Author(s): jiri.raska

## Context

ADR 0003 mandates the transactional outbox pattern for every service that emits
Kafka events. Across ~20 services the pattern has been re-implemented per service:
each owns its own `*OutboxEntity`, `*OutboxRepository`, `*OutboxDispatcher` and
`Kafka*OutboxEventPublisher`. The SQL schema, the dispatcher loop body, the
status enum and the persistence helpers are byte-identical apart from the table
name and the Kafka channel name.

Audit (2026-05-28) measured ~1 000 lines of duplicated Kotlin and ~600 lines of
duplicated SQL across services, with corresponding divergence risk: a fix to the
dispatcher (e.g. `attemptCount` semantics, dead-letter handling, observability)
must today be applied 20 times.

## Decision

We will move the **invariant parts** of the outbox pattern into
`openbank-libs/persistence/outbox`:

- `OutboxStatus`, `OutboxMessage`, `OutboxEntry` — shared DTOs.
- `OutboxRepository`, `OutboxEventPublisher` — shared interfaces.
- `AbstractOutboxEntity` — `@MappedSuperclass` with the 10 common columns
  (`event_id`, `aggregate_id`, `event_type`, `payload`, `status`,
  `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`).
- `OutboxDispatch.dispatchOnce(repository, publish)` — the loop body.

Each service keeps:

- its own `@Entity @Table(name = "<service>_outbox")` subclass of
  `AbstractOutboxEntity` (one-line declaration);
- its own `@ApplicationScoped` `PanacheRepository<E>` implementing
  `OutboxRepository`;
- its own `@Channel("<service>-events-out")` Kafka publisher implementing
  `OutboxEventPublisher`;
- its own `@Scheduled` dispatcher method that calls
  `OutboxDispatch.dispatchOnce(...)` with the service-side
  `@CircuitBreaker @Retry @Bulkhead @Timeout` annotations on the publish lambda.

We keep `@Scheduled` and resilience annotations service-side on purpose:
MicroProfile Fault Tolerance interceptors only fire on direct CDI proxies, so a
libs-side abstract dispatcher would silently lose them.

## Alternatives considered

- **Quarkus extension (`openbank-quarkus-outbox`)**. Auto-registers everything,
  but requires a build-time processor and adds a runtime dependency every
  service must inherit. Reject for now — premature; we can promote the libs
  primitives to an extension once the API has stabilised.
- **Single shared `outbox` table in a dedicated service**. Couples every
  service to that service's availability and breaks ADR 0009
  (postgres-per-service). Rejected.
- **Leave as is.** The duplication will keep diverging and the next dispatcher
  bug must be fixed N times. Rejected.

## Consequences

**Positive**
- Single source of truth for the schema and the dispatch loop.
- New services adopt the pattern with ~30 lines instead of ~250.
- A fix to dead-letter handling, batch sizing or observability lands once.
- DORA Art. 25 (operational resilience for ICT services that support critical
  functions) becomes auditable in one place.

**Negative**
- Migration is per-service work; existing services keep their bespoke code
  until they're migrated. The shared and the bespoke versions coexist for the
  duration of the migration.
- Schema changes to `AbstractOutboxEntity` require a Flyway migration in every
  service that uses it.

**Neutral**
- The libs JAR gains `compileOnly` declarations for `jakarta.persistence-api`,
  `quarkus-redis-client` and `mutiny-kotlin`. Runtime services already bring
  the matching `implementation` dependencies.

## Migration plan

1. New services use the libs primitives from day one.
2. When touching an existing outbox for any reason, migrate it as part of that
   work. Order of attack (most-changed first): account, transaction, sepa-payment,
   domestic-payment, sca, consent.
3. Track migrated services in `docs/strategy/07-compliance-matrix.md` under the
   DORA Art. 25 row.

## Compliance impact

- DORA:  Art. 25 (operational resilience for ICT services). Centralising
  dispatch and retry semantics removes per-service drift.
- CNB:   Vyhláška 163/2014 §8 (ICT outsourcing risk). The outbox is the
  exit-safe boundary between service state and downstream consumers.
- PCI DSS: not applicable (no cardholder data in the outbox).
- GDPR:  not applicable directly; payloads carry whatever the domain event
  carries.
- PSD2:  not applicable directly.

## References

- ADR 0003 — Transactional outbox for Kafka.
- ADR 0009 — Postgres per service.
- Audit 2026-05-28: outbox duplication across services.
