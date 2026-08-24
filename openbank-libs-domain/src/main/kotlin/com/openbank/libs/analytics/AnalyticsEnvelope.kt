// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import java.time.Instant
import java.time.Period
import java.util.UUID

/**
 * Canonical record that lands in the analytics **bronze** layer (ADR-0022).
 *
 * The analytics layer is fed from the same domain events the transactional outbox already
 * publishes to Kafka (ADR-0003) — it is *not* a second extraction path (no Debezium/WAL CDC).
 * Each event is normalised into this envelope before it is written, so the bronze layer is a
 * uniform, append-only, replayable log of record across all ~28 services.
 *
 * Provenance fields ([actorId], [actorType], [traceId], [sourceService], [occurredAt]) mirror
 * [com.openbank.libs.audit.AuditEvent], so every analytics row carries audit-grade lineage for
 * free (GDPR Art. 30 / DORA Art. 17).
 *
 * Consistency model (eventual): Kafka is at-least-once, so the bronze layer must dedupe on
 * [eventId] and order per aggregate on [aggregateVersion] — see [AnalyticsProjections]. The
 * ClickHouse target encodes this as `ReplacingMergeTree([aggregateVersion])` keyed by
 * (aggregateType, aggregateId); this envelope is the producer-side contract for that.
 *
 * Long-horizon interpretability: [schemaVersion] records the contract version the [payload] was
 * produced under, so an event written today is still decodable years later when the producing
 * service's schema has evolved (see [AnalyticsRetention]).
 */
data class AnalyticsEnvelope(
    /** Globally unique event id — the dedupe key. Sourced from the outbox event / `AuditEvent.eventId`. */
    val eventId: UUID,
    /** Aggregate category, e.g. `ACCOUNT`, `PARTY`, `TRANSACTION`. */
    val aggregateType: String,
    /** Aggregate identifier within its type. */
    val aggregateId: String,
    /** Monotonic per-aggregate version for ordering / last-writer-wins. 0 if the source has none. */
    val aggregateVersion: Long,
    /** Domain event type, e.g. `account.party.created`. */
    val eventType: String,
    /** Business time the event occurred (from the source event). */
    val occurredAt: Instant,
    /** Emitting service, e.g. `openbank-account-service`. */
    val sourceService: String,
    /** Contract/schema version the [payload] was produced under (Avro/Apicurio), for long-term decodability. */
    val schemaVersion: Int,
    /** Who triggered the change (audit provenance); null for system-originated events. */
    val actorId: String? = null,
    /** Most-specific actor role (audit provenance). */
    val actorType: String? = null,
    /** Correlation/trace id tying this row back to logs and the audit trail. */
    val traceId: String? = null,
    /**
     * How this row entered the bronze layer (lineage). Defaults to [IngestSource.STREAM] — the
     * normal live outbox path. Backfill / initial-load / correction batches set this so every row
     * is self-describing: an auditor can tell a live event from a row reloaded by a corrected batch.
     */
    val ingestSource: IngestSource = IngestSource.STREAM,
    /**
     * Identifier of the batch that produced this row, for non-[IngestSource.STREAM] ingests. Lets a
     * specific reload/correction run be traced, counted and (if wrong) superseded. Null for live stream.
     */
    val batchId: String? = null,
    /** When this envelope was materialised into the bronze layer. */
    val ingestedAt: Instant = Instant.EPOCH,
    /** PII-masked event body. NEVER raw PII — mask with [com.openbank.libs.security.PiiMask] at the sink. */
    val payload: Map<String, Any?> = emptyMap(),
    /**
     * True only for a bank-owned synthetic customer's activity. Bronze retains the audit trail;
     * baseline projections must exclude it so test traffic cannot contaminate regulatory or BI facts.
     */
    val synthetic: Boolean = false,
)

/**
 * Provenance of how an [AnalyticsEnvelope] reached the bronze layer.
 *
 * The bronze layer is idempotent on [AnalyticsEnvelope.eventId] and last-writer-wins on
 * [AnalyticsEnvelope.aggregateVersion], so re-ingesting the *same* events from any source is safe.
 * This enum exists for **auditability and operations**, not for consistency: it answers "where did
 * this row come from" and powers the recovery flows in [AnalyticsProjections] / the sink service.
 */
enum class IngestSource {
    /** Normal live consumption of the outbox event stream (Kafka). */
    STREAM,

    /**
     * One-time seed of pre-existing OLTP state captured *before* the stream was switched on, so the
     * warehouse has history that predates the sink. Emitted as a synthetic version-0-or-current row.
     */
    INITIAL_LOAD,

    /**
     * Replay of a time/aggregate window from the durable source of record (the per-service outbox,
     * NOT Kafka — Kafka retention is finite) to fill a gap left by an outage longer than Kafka retention.
     */
    BACKFILL,

    /**
     * Deliberate restatement: a corrected batch re-published with a higher [AnalyticsEnvelope.aggregateVersion]
     * so silver/gold converge to the fixed value without mutating or deleting the original bronze rows.
     */
    CORRECTION,
}

/**
 * Retention policy for the analytics layers (ADR-0022).
 *
 * The bronze layer is the **log of record** — projections are rebuilt from it, not from Kafka
 * (Kafka retention is finite). It must therefore be kept for at least the regulatory floor;
 * deleting bronze earlier is irreversible and forfeits the ability to recompute or reconcile.
 */
object AnalyticsRetention {
    /**
     * Regulatory minimum the bronze layer must be retained. Banking record-keeping obligations
     * (e.g. AML/accounting) commonly require ~10 years; this is the floor, never the ceiling.
     * The ClickHouse `TTL` on the bronze table must be >= this.
     */
    val BRONZE_MINIMUM: Period = Period.ofYears(10)
}
