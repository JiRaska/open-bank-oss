# Data

## Persistence model

This service owns **no OLTP / PostgreSQL database** and uses **no Flyway** — intentionally (ADR-0022). Its store of record is the **ClickHouse analytics warehouse**, database `openbank_analytics`. The DDL is source-controlled in `src/main/resources/clickhouse/V1__analytics_bronze_silver.sql` and is applied by an **operator** (or the future `ClickHouseAnalyticsSink` bootstrap), **not** by a migration runner.

The default `LoggingAnalyticsSink` needs no external system, so the service boots and is testable with zero infrastructure; the durable `ClickHouseAnalyticsSink` is activated by `openbank.analytics.sink.type=clickhouse`.

## Medallion layering

| Layer | Object | Engine | Purpose |
|---|---|---|---|
| **bronze** | `bronze_events` (table) | `ReplacingMergeTree(aggregate_version)` | Append-only **log of record** — one row per ingested envelope, duplicates tolerated (collapsed at merge/FINAL). |
| **silver** | `silver_current_state` (view) | view over bronze (`argMax`) | Current-state-per-aggregate, last-writer-wins, mirrors `AnalyticsProjections.latestPerAggregate`. |
| **silver** | `silver_history` (view) | view (`leadInFrame`) | SCD2 valid-from/valid-to version history per aggregate. |
| **silver** | `silver_as_of` (parameterised view) | view, `WHERE occurred_at <= {t}` | Point-in-time "report as-of" current state. |
| **gold** | `gold_daily_event_volume` (view) | view | Daily event-volume rollup per service/type — where BI dashboards point. |

## Operational / compliance tables

| Table | Engine | Retention (TTL) | Purpose |
|---|---|---|---|
| `bronze_events` | `ReplacingMergeTree(aggregate_version)`, `PARTITION BY toYYYYMM(occurred_at)` | **10 years** (floor, never lower) | The log of record. |
| `dead_letter_events` | `ReplacingMergeTree(failed_at)` | 1 year | Un-projectable / unknown-schema messages, quarantined (not dropped), idempotent on `content_hash`, replayable. |
| `backfill_audit` | `MergeTree` | 10 years | One row per recovery load (who/what/why + counts) — evidence a gap was deliberately filled. |
| `integrity_anchors` | `MergeTree` | **no TTL** (outlives every record) | Merkle root per sealed batch, chained to previous anchor — tamper-evidence (ADR-0023). Authoritative copy lives in WORM/S3 Object Lock; this is a queryable mirror. |
| `reload_proposals` | `ReplacingMergeTree(updated_at)` | 10 years | Maker-checker decision trail (PROPOSED→APPROVED/REJECTED/WITHDRAWN→EXECUTED). |

## `bronze_events` columns (selected)

| Column | Type | Notes |
|---|---|---|
| `event_id` | `UUID` | Dedupe key; bloom-filter index `idx_event_id`. |
| `aggregate_type` / `aggregate_id` | `LowCardinality(String)` / `String` | e.g. `ACCOUNT`, `PARTY`, `TRANSACTION`, `CONSENT`, `KYC_CASE`. |
| `aggregate_version` | `Int64` | `ReplacingMergeTree` version key (last-writer-wins). |
| `event_type`, `occurred_at`, `source_service`, `schema_version` | — | Event identity / provenance. |
| `actor_id`, `actor_type`, `trace_id` | `Nullable` | Provenance / tracing. |
| `ingest_source`, `batch_id`, `ingested_at` | — | Lineage: `STREAM` / `INITIAL_LOAD` / `BACKFILL` / `CORRECTION` + reload batch. |
| `record_hash` | `String` | Deterministic SHA-256 over row identity + content (tamper-evidence). |
| `payload` | `String` (JSON) | **PII-masked** event body — never raw PII. |

## PII handling

PII is **masked at the ingestion boundary** by `PayloadMasker`, before anything durable is written — the bronze layer is retained ≥10 years, so it must never hold raw identifiers (GDPR Art. 25 data-protection-by-design).

| Field name (case-insensitive) | Masking strategy |
|---|---|
| `email`, `emailaddress` | `EMAIL` |
| `iban`, `accountnumber` | `IBAN` |
| `pan`, `cardnumber` | `PAN` |
| `phone`, `phonenumber`, `msisdn` | `PHONE` |
| `name`, `fullname`, `firstname`, `lastname` | `NAME` |
| `nationalid`, `birthnumber`, `rodnecislo`, `ssn` | `NATIONAL_ID` |

Masking is **conservative allow-by-default**: a recognised key is masked irreversibly; an unrecognised field passes through structurally (still carries analytic value). The retained `aggregateId` is a **pseudonym**, not a direct identifier.

## Retention & erasure

- **Bronze floor: 10 years** (`AnalyticsRetention.BRONZE_MINIMUM`; ClickHouse TTL set to match). This is a floor (raise, never lower) — deleting bronze is irreversible and forfeits recompute/reconcile.
- **Erasure** (GDPR Art. 17) is handled by `ErasureService` applying per-category `RetentionPolicies`: erasable categories are **crypto-shredded** (`VaultCryptoErasure` when `erasure.backend=vault`); categories under an AML/accounting statutory hold are **refused** with a documented legal basis (Art. 17(3)(b)). Because only the pseudonymous id remains after sink-time masking, the residual data is already minimised.
