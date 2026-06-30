# 22. Analytics/reporting layer: event-fed ClickHouse, not CDC, not a lakehouse

Date: 2026-05-29
Status: Accepted
Delivery-Status: Partial
Author(s): Jiří Raška

**Delivery note (updated 2026-06-30):**
- **Orchestration/decision logic** — ✅ Shipped: bronze/silver medallion views, PII masking, as-of/SCD2 reporting, reconciliation diffs, date-travel for regulatory snapshots all working and tested.
- **External integrations** — ⬜ Deferred: ClickHouse client, outbox/export BackfillSource reader, OLTP/warehouse reconciliation readers remain `@Default` no-op stubs; ClickHouse infra tracked separately.

## Context

OpenBank needs a reporting/analytics layer for dashboards, regulatory reports and
operational insight. Three hard constraints shape it:

1. **It must not load the operational system.** Reporting queries must never run against the
   per-service Postgres databases (ADR-0009); a few months of growth plus a few analysts
   running ad-hoc queries would degrade customer-facing latency.
2. **It must stay consistent over very long, large data** — the analytics store of record
   must be retained **at least 10 years** (banking record-keeping: AML/accounting), and must
   remain decodable and reconcilable across that horizon even as producing services evolve.
3. **It must be audit-bulletproof and operable by a small team** — no heavyweight cluster we
   cannot run (the same reasoning that rejected a Temporal cluster in ADR-0045).

The obvious "modern" answer is CDC (Debezium/WAL) into a lakehouse (Iceberg + Trino + dbt +
Dagster). We reject both ends of that.

**Why not CDC.** Debezium/WAL replication is a *second extraction path* straight out of the
operational databases. It couples analytics to physical OLTP schemas, adds replication-slot
load and operational risk to the very databases we are trying to protect, and it bypasses the
domain meaning the services already encode. We already publish every state change as a domain
event through the transactional outbox (ADR-0003). That stream is the natural, decoupled,
already-paid-for extraction path — a Kappa-style architecture. CDC would duplicate it while
adding OLTP coupling.

**Why not a full lakehouse.** Iceberg/Trino/dbt/Dagster is real infrastructure with real
operational cost. For OpenBank's volume it is overkill and contradicts the project's own
operability philosophy. We can adopt object-store + table-format later *as an opt-in* without
changing the producer contract, because the bronze layer is already an append-only log.

## Decision

**Event-fed medallion analytics in ClickHouse, with the bronze layer as the 10-year log of
record.**

**1. One extraction path: the existing outbox stream.** A new stateless service,
`openbank-analytics-sink`, consumes the same Kafka domain-event topics the audit service
already ingests (ADR-0003). There is **no** Debezium/WAL CDC. The service owns **no** OLTP
database (no Panache/Flyway/pg-client in its build) — reporting therefore adds *zero* read
load on the operational system.

**2. A canonical envelope in openbank-libs.** Every event is normalised into
`com.openbank.libs.analytics.AnalyticsEnvelope` before it is written: stable ids, aggregate
type/id, a monotonic `aggregateVersion`, business `occurredAt`, `schemaVersion` (so a row
written today stays decodable years later), and audit-grade provenance fields mirroring
`AuditEvent`. PII in the payload is **masked at the sink** via `PiiMask` — the long-lived
bronze layer never holds raw identifiers (GDPR Art. 25/17).

**3. Medallion in ClickHouse.**
   - *bronze* — append-only `ReplacingMergeTree(aggregate_version)` keyed by
     `(aggregate_type, aggregate_id, event_id)`, partitioned by month. The **log of record**:
     projections are rebuilt from it, not from Kafka (Kafka retention is finite).
   - *silver* — a `argMax`/`FINAL` current-state-per-aggregate view (last-writer-wins).
   - *gold* — cheap rollup views/marts that BI tools (Metabase/Superset) point at, so
     dashboards never touch OLTP.

**4. Consistency model (eventual, but provable).** Kafka is at-least-once, so:
   - **Dedupe on `eventId`** — `AnalyticsProjections.dedupeByEventId` at the sink + a
     `ReplacingMergeTree` + bloom-filter index in the warehouse.
   - **Per-aggregate last-writer-wins on `aggregateVersion`** (tie-broken by
     `occurredAt` then `ingestedAt`) — `AnalyticsProjections.latestPerAggregate` computes the
     *same* current-state view in-process that the ClickHouse `silver` view computes, so an
     in-app reconciliation job and the warehouse agree by construction.
   - **Bronze is the log of record** — not Kafka — so any projection can be recomputed within
     the retention window.
   - **Periodic reconciliation** — `ReconciliationJob` runs off-peak (cron, not fixed-rate)
     to compare OLTP current-state against the warehouse `FINAL` view and surface drift; a
     `ROLE_AUDITOR`-gated REST endpoint triggers it on demand and exposes the last result as
     audit evidence (warehouse == source-of-record at time T).

**5. 10-year retention as a floor in code and DDL.**
   `AnalyticsRetention.BRONZE_MINIMUM = Period.ofYears(10)` is the single source of truth; the
   bronze table's `TTL occurred_at + INTERVAL 10 YEAR` mirrors it. This is the floor, never the
   ceiling — raising it is safe, lowering it is irreversible and forfeits recompute/reconcile.

**6. ClickHouse adapter is a documented follow-up.** The default `AnalyticsSink` binding is
`LoggingAnalyticsSink` (structured log line, no infra) so the service boots, is unit-tested and
is offline-buildable today. The durable ClickHouse client lands as an
`@Alternative @Priority(...)` adapter — the same fallback pattern as
`LoggingAuditEventPublisher`. A missing warehouse connection must never *silently drop* bronze.

## Will it stay consistent for very long / large data?

Yes, by design rather than by hope:
- **Size** — columnar compression + monthly partitions + `LowCardinality` keys keep 10 years
  tractable; old partitions are cheap and independently TTL-able.
- **Correctness** — dedupe + version-keyed replacement make ingestion idempotent and order-
  independent (out-of-order/at-least-once delivery converges to the same state).
- **Decodability** — `schemaVersion` on every row means an event written under an old contract
  stays interpretable after the producing service's schema evolves.
- **Provability** — reconciliation + the in-libs projection give a point-in-time tie-out
  between OLTP and the warehouse for regulators.

## Consequences

**Positive.** No OLTP read load from reporting; one well-understood store (ClickHouse) a small
team can run; audit-grade lineage for free; deterministic, tested consistency primitives shared
between sink and reconciliation; 10-year retention enforced in both code and DDL; a clean
upgrade path to object-store/lakehouse later without changing the producer contract.

**Negative / trade-offs.** Eventual (not strong) consistency — there is ingestion lag, mitigated
by reconciliation and acceptable for reporting. Bronze stores PII-masked payloads as JSON, so
some analyses need silver/gold projections to extract typed columns. Reconciliation count/hash
comparison and the ClickHouse adapter are stubbed pending implementation. Two stores now ingest
the same event stream (audit + analytics); this is intentional (different retention, masking and
query profiles) but is duplicated consumer wiring to keep in sync.

## Addendum (2026-05-29): operational recovery — closing the batch-DWH gaps

A critical self-review found the first cut covered streaming consistency (idempotent dedupe,
correction-by-version, late/out-of-order) but **not** the classic batch-DWH recovery muscles.
Those are now first-class. The honest correction to claim (2) above: the durable replay source is
**not Kafka** (finite retention) — it is the per-service **outbox** (ADR-0003) or its archive/export.

**7. Backfill from the durable source of record.** `BackfillRequest` + `BackfillPlanner` (in libs,
tested) + `BackfillSource` port + `BackfillService` reload a time/aggregate window through the *same*
mapping + PII-masking path as the live consumer. Windows are chunked so a multi-year reload is
restartable and each warehouse insert is bounded. This fills a gap left by an outage **longer than
Kafka retention** — the live stream alone cannot. (Reader adapter on the outbox/export is the
documented follow-up; orchestration, chunking, dedupe, tagging and reporting are real today.)

**8. Initial load.** `IngestSource.INITIAL_LOAD` seeds pre-existing OLTP state captured *before* the
stream was switched on, so the warehouse has history predating the sink — the full-load step every
real DWH starts with.

**9. Corrections / restatements.** `IngestSource.CORRECTION` re-publishes a fixed batch with higher
`aggregateVersion`; silver/gold converge to the corrected value **without mutating or deleting** the
original bronze rows (the log of record stays immutable; the *view* of current state changes).

**10. Lineage on every row.** `ingestSource` + `batchId` on the envelope (and bronze columns) make
each row self-describing — an auditor can tell a live event from a row reloaded by a named corrected
batch, and a wrong batch can be traced and superseded. `backfill_audit` records who reloaded what and why.

**11. Dead-letter quarantine (DLQ).** The consumer no longer "logs and swallows" malformed events —
it quarantines them to a `DeadLetterSink` (idempotent on a content hash; `dead_letter_events` table),
so a dropped event is never an invisible gap and is replayable once the producer is fixed.

**12. As-of / SCD2 reporting.** `AnalyticsProjections.asOf` + `history` (tested) and the
`silver_as_of` (parameterised) + `silver_history` ClickHouse views give point-in-time "report as of
date T" and slowly-changing-dimension history — reconstructable exactly because bronze is a full
event log.

**13. Real reconciliation.** `Reconciliation.diff` (pure, tested) compares `AggregateKey -> maxVersion`
from `ReconciliationSource` (OLTP) and `WarehouseStateReader` (ClickHouse), transferring only versions
(no payloads), and classifies drift into missing-in-warehouse / orphan / version-mismatch. The job
**reports** drift (off-peak cron + `ROLE_AUDITOR` trigger) but does **not** auto-remediate — reloading
a 10-year store is a deliberate operator action via the backfill endpoint.

**GDPR stance on `aggregateId`.** Payloads are PII-masked at the sink, but `aggregateId` (e.g. a
partyId) is retained in clear as a **pseudonymous** key for the regulatory-retention period. This is
the legal-obligation basis (GDPR Art. 17(3)(b)) — erasure of directly-identifying data happens in OLTP
(crypto-shred / tombstone, see K5); the analytics layer keeps only the pseudonym needed to satisfy the
record-keeping obligation, which is the documented, defensible position.

**Still stubbed (honest):** the ClickHouse client, the outbox/export `BackfillSource` reader, and the
OLTP/warehouse reconciliation readers are `@Default` no-ops so the service is offline-buildable. All
*orchestration and decision logic* around them is implemented and unit-tested.

## References
- ADR-0003 — transactional outbox + Kafka (the single extraction path)
- ADR-0009 — Postgres-per-service (what we are protecting from reporting load)
- ADR-0045 — lightweight-over-cluster operability philosophy
- `openbank-libs` — `analytics.AnalyticsEnvelope`, `analytics.AnalyticsProjections`,
  `analytics.AnalyticsRetention`, `security.PiiMask`, `audit.AuditEvent`
- `openbank-analytics-sink` — sink service + `clickhouse/V1__analytics_bronze_silver.sql`
