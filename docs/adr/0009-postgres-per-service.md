# 9. Postgres per service (database-per-service)

Date: 2026-05-26
Status: Accepted
Delivery-Status: Shipped

## Context

Microservices architectures debate between:
- **Shared database**: multiple services read/write the same DB — simple but couples services through schema.
- **Schema-per-service in shared DB**: each service owns a Postgres schema in a shared DB cluster — operational simplicity.
- **Database-per-service**: each service has its own DB cluster — full isolation, independent scaling.

In a banking platform, schema coupling is the single biggest source of cross-team breakage. A "small ALTER" in one service breaks five others sharing the table.

## Decision

OpenBank adopts **database-per-service** as the default:

- Each service owns its Postgres database (or, at minimum, schema in a dedicated cluster).
- A service NEVER queries another service's DB directly. All inter-service data flows through APIs or events.
- Services that genuinely share a bounded context (e.g. account + balance + ledger forming the ledger core) MAY co-locate in one DB cluster with separate schemas, documented in their ADR.
- Migrations: each service has its own `db/migration/` Flyway directory; migrations run on service startup or via separate Flyway job.
- No cross-service foreign keys.
- Reference data (party master, country lookups) is replicated via events, not joined across DBs.

Tier-A operators may shard further (per-customer-segment shards within a service); this is operator concern, not maintainer concern.

## Consequences

**Positive**
- Schema changes localised; one service's migration cannot break another.
- Services scale storage independently.
- Failure isolation: one DB outage takes down one service, not all of them.
- Each service's DB can use the best-fit Postgres extensions / config.

**Negative**
- More DB clusters to operate; higher ops cost.
- Cross-service joins are impossible; consumers do data composition.
- Reference data duplication; eventual consistency.

**Mitigation**
- Use PgBouncer / managed Postgres to reduce per-cluster operational overhead.
- Provide data-composition libraries (BFF pattern, GraphQL gateway) to handle UI joins.
- Reference data sync via Kafka + CDC keeps duplicates consistent.

## References

- Chris Richardson, "Microservices Patterns" — Database per Service
- Sam Newman, "Building Microservices" 2nd ed
