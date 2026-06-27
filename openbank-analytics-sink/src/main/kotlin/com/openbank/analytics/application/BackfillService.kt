// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.analytics.application.port.out.BackfillSource
import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.WormArchive
import com.openbank.libs.analytics.AnalyticsIntegrity
import com.openbank.libs.analytics.AnalyticsProjections
import com.openbank.libs.analytics.BackfillPlanner
import com.openbank.libs.analytics.BackfillRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/** Audit record of one backfill / initial-load / correction run. Surfaced to the operator + DLQ-style logs. */
data class BackfillReport(
    val batchId: String,
    val source: String,
    val from: Instant,
    val to: Instant,
    val windows: Int,
    val ingested: Int,
    val deduped: Int,
    val requestedBy: String,
    val reason: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val status: String,
)

/**
 * Orchestrates recovery loads into the bronze layer (ADR-0022): outage **backfill**, **initial load**
 * of pre-existing OLTP state, and **corrections**.
 *
 * Flow per request: [BackfillPlanner.chunk] the window into restartable sub-windows → [BackfillSource.read]
 * raw events for each → map + PII-mask through the *same* [AnalyticsConsumer.toEnvelope] path the live
 * stream uses → tag every row with the run's [com.openbank.libs.analytics.IngestSource] and `batchId`
 * (lineage) → [AnalyticsProjections.dedupeByEventId] → [AnalyticsSink.writeBatch]. Re-ingestion is safe
 * by construction (dedupe on eventId + last-writer-wins on aggregateVersion), so a backfill overlapping
 * live data, or run twice, converges to the same state.
 */
@ApplicationScoped
class BackfillService {

    @Inject lateinit var source: BackfillSource

    @Inject lateinit var clock: Clock

    @Inject lateinit var sink: AnalyticsSink

    @Inject lateinit var consumer: AnalyticsConsumer

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var worm: WormArchive

    /** Chunk size for splitting a (possibly multi-year) reload into bounded, committable windows. */
    @ConfigProperty(name = "openbank.analytics.backfill.chunk", defaultValue = "PT24H")
    lateinit var chunk: Duration

    private val log = Logger.getLogger(BackfillService::class.java)
    private val lastRun = AtomicReference<BackfillReport?>(null)

    suspend fun run(request: BackfillRequest): BackfillReport {
        val batchId = UUID.randomUUID().toString()
        val startedAt = Instant.now(clock)
        val windows = BackfillPlanner.chunk(request, chunk)
        var ingested = 0
        var deduped = 0
        val batchHashes = ArrayList<String>()

        for (window in windows) {
            val envelopes = source.read(window, request)
                .mapNotNull { raw -> runCatching { consumer.toEnvelope(objectMapper.readTree(raw)) }.getOrNull() }
                .map { it.copy(ingestSource = request.source, batchId = batchId) }
            val unique = AnalyticsProjections.dedupeByEventId(envelopes)
            deduped += envelopes.size - unique.size
            sink.writeBatch(unique)
            ingested += unique.size
            unique.mapTo(batchHashes) { AnalyticsIntegrity.recordHash(it) }
        }

        // Seal a tamper-evidence anchor for the whole reload batch (F1+F2): a Merkle root over the
        // record hashes, chained to the previous anchor. A later challenge re-derives the hashes from
        // bronze and checks they still produce this root. Sealing failure degrades evidence, not the
        // write, so it must never abort the reload — but it is logged loudly by the WORM adapter.
        if (batchHashes.isNotEmpty()) {
            runCatching { sealAnchor(batchId, batchHashes, request.source.name) }
                .onFailure { log.errorf(it, "failed to seal integrity anchor for batchId=%s", batchId) }
        }

        val report = BackfillReport(
            batchId = batchId,
            source = request.source.name,
            from = request.from,
            to = request.to,
            windows = windows.size,
            ingested = ingested,
            deduped = deduped,
            requestedBy = request.requestedBy,
            reason = request.reason,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
            status = "COMPLETED",
        )
        lastRun.set(report)
        log.infof(
            "analytics backfill batchId=%s source=%s windows=%d ingested=%d deduped=%d by=%s reason=%s",
            batchId,
            report.source,
            report.windows,
            ingested,
            deduped,
            request.requestedBy,
            request.reason,
        )
        return report
    }

    fun lastReport(): BackfillReport? = lastRun.get()

    private suspend fun sealAnchor(batchId: String, hashes: List<String>, source: String) {
        val previous = worm.latest()
        worm.seal(
            IntegrityAnchor(
                anchorId = batchId,
                merkleRoot = AnalyticsIntegrity.merkleRoot(hashes),
                previousAnchorHash = previous?.let { AnalyticsIntegrity.recordHashOfString(it.merkleRoot) },
                recordCount = hashes.size,
                source = source,
                sealedAt = Instant.now(clock),
            ),
        )
    }
}
