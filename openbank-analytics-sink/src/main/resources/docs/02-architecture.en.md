# Architecture

## C4 — container view

```
        ┌──────────────────────────────────────────────────────────────┐
        │                    openbank-analytics-sink                     │
        │                                                                │
 Kafka  │  ┌───────────────────┐   AnalyticsEnvelope   ┌──────────────┐ │
 topics ─┼─►│ AnalyticsConsumer │ ───── (masked) ─────► │ AnalyticsSink│ │──► ClickHouse
        │  │  @Incoming        │                       │   (port)     │ │   bronze/silver/gold
        │  └─────────┬─────────┘                       └──────────────┘ │
        │            │ un-projectable / unknown schema                  │
        │            ▼                                                   │
        │       DeadLetterSink ──► dead_letter_events (quarantine)       │
        │                                                                │
 REST   │  ┌──────────────┐ ┌────────────────────┐ ┌───────────────┐    │
 (8134) ─┼─►│ BackfillRes. │ │ ReconciliationRes. │ │ ErasureRes.   │    │
        │  └──────┬───────┘ └─────────┬──────────┘ └──────┬────────┘     │
        │   SensitiveReload      ReconciliationJob    ErasureService     │
        │   (maker-checker)      (vs source of record)  (crypto-shred)   │
        │            │                  │                   │            │
        │   ProposalStore        ReconciliationPorts    CryptoErasure    │
        │   WormArchive (anchors)  BackfillSource         (Vault)        │
        └──────────────────────────────────────────────────────────────┘
            │ mgmt 8086 /q (health, metrics, docs)
```

## Hexagonal layers (ADR-0002)

The service follows the platform's hexagonal split. The domain/contract types live in **`openbank-libs` `com.openbank.libs.analytics`** (`AnalyticsEnvelope`, `AnalyticsProjections`, `BackfillRequest`, `Proposal`/maker-checker, `RetentionPolicies`, `Integrity`, `Reconciliation`), keeping framework-free domain logic shared across the platform.

### Application layer — `com.openbank.analytics.application`
Orchestration, no framework I/O coupling:
- **`AnalyticsConsumer`** — the only ingestion path. Reads raw JSON from Kafka, builds an `AnalyticsEnvelope`, masks PII, applies schema governance, writes via the sink, records freshness.
- **`PayloadMasker`** — field-name PII masking (email, IBAN, PAN, phone, name, national-id/rodné číslo) using `libs.security.PiiMask` strategies, recursive over the JSON body.
- **`SchemaGovernance`** — accepts/quarantines events by `eventType:schemaVersion` against a catalogue (config or Apicurio).
- **`SensitiveReloadService`** — four-eyes recovery-load lifecycle (PROPOSED→APPROVED/REJECTED→EXECUTED) over a `Proposal` state machine.
- **`BackfillService`** — runs the actual chunked reload windows.
- **`ErasureService`** — applies per-category `RetentionPolicies`; crypto-shreds erasable data, refuses statutory-held categories with a documented legal basis.
- **`IngestFreshness`** — tracks ingest lag and dead-letter count for the readiness probe.

### Application ports (out) — `com.openbank.analytics.application.port.out`
- `AnalyticsSink` — write an envelope to the warehouse bronze layer.
- `DeadLetterSink` — quarantine an un-projectable / quarantined message.
- `SchemaCatalogSource` — supply the accepted `eventType:version` catalogue.
- `WormArchive` — seal tamper-evidence integrity anchors into immutable storage.
- `ErasurePort` / `CryptoErasure` — crypto-shred a pseudonymous aggregate key.
- `BackfillSource` — supply events for a reload window.
- `ProposalStore` — persist the maker-checker proposal trail.
- `ReconciliationPorts` — warehouse-state and source-of-record readers.

### Infrastructure layer — `com.openbank.analytics.infrastructure`
Adapters bound by build-time config, each with a zero-infra default so the service is offline-buildable:
- **sink:** `LoggingAnalyticsSink` (`@Default`) / `ClickHouseAnalyticsSink` (`type=clickhouse`); `LoggingDeadLetterSink`.
- **clickhouse:** `ClickHouseClient` (HTTP interface), `ClickHouseAnalyticsSink`, `ClickHouseProposalStore`, `ClickHouseWormArchive`, `ClickHouseWarehouseStateReader`.
- **schema:** `ConfigSchemaCatalogSource` (`@Default`) / `ApicurioSchemaCatalogSource` (`backend=apicurio`).
- **erasure:** `NoOpCryptoErasure` (`@Default`) / `VaultCryptoErasure` (`backend=vault`).
- **worm:** `LoggingWormArchive` / `ClickHouseWormArchive` / `S3WormArchive` (`backend=s3`, S3 Object Lock COMPLIANCE mode).
- **reconcile:** `ReconciliationJob` (scheduled cron + manual), `NoOpReconciliationPorts`/`HttpReconciliationSource` (fans out to each service's role-gated reconciliation-summary endpoint), `NoOpBackfillSource`.
- **rest:** `BackfillResource`, `ReconciliationResource`, `ErasureResource`, `MakerCheckerExceptionMapper`.
- **health:** `IngestHealthCheck` (readiness on lag/DLQ).
- **`DataResidencyValidator`** — startup guard (boot aborts if region not on allow-list).

## Event → bronze flow

1. A producing service writes a domain event to its transactional outbox; the outbox relays it to Kafka (ADR-0003).
2. `AnalyticsConsumer.@Incoming("analytics-events-in")` receives the raw JSON (at-least-once).
3. The event is mapped to an `AnalyticsEnvelope`; `aggregateType`/`aggregateId`/`version` are inferred from well-known field names when not explicit.
4. `PayloadMasker` masks PII leaves; `SchemaGovernance` checks the schema (quarantine if `strict` and unknown).
5. The envelope is written via `AnalyticsSink`. ClickHouse `ReplacingMergeTree(aggregate_version)` collapses duplicates; `eventId` is the dedupe key.
6. A `record_hash` is computed per row; batches are sealed into Merkle `integrity_anchors`, chained and (optionally) mirrored to S3 Object Lock WORM.
7. Any failure quarantines the raw message to `dead_letter_events` (never silently dropped) and bumps the dead-letter freshness counter.

## Key design decisions

- **No outbox here.** This is a downstream consumer; it emits no domain events.
- **Build-time adapter selection.** Every external dependency (ClickHouse, Vault, S3, Apicurio, HTTP reconciliation source) is opt-in via `openbank.analytics.*.backend`/`type`, defaulting to an offline no-op/logging binding (ADR-0026 pattern), so the default build needs zero infrastructure.
- **Bronze is the log of record** (ADR-0022); silver/gold are derived views, single-copy storage.
- **Tamper-evidence outside ClickHouse** — ClickHouse is operator-mutable, so the authoritative integrity chain lives in WORM (S3 Object Lock); the ClickHouse `integrity_anchors` table is a queryable mirror (ADR-0023).
