// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.ReconciliationSource
import com.openbank.analytics.application.port.out.WarehouseStateReader
import com.openbank.analytics.application.port.out.WormArchive
import com.openbank.libs.analytics.AggregateKey
import com.openbank.libs.analytics.Completeness
import com.openbank.libs.analytics.CountDelta
import com.openbank.libs.analytics.Reconciliation
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Periodic OLTP-vs-warehouse **drift check** (ADR-0022) and the regulatory tie-out hook.
 *
 * Pulls `AggregateKey -> maxVersion` from both the OLTP source ([ReconciliationSource]) and the
 * warehouse ([WarehouseStateReader]) and compares them with the pure, unit-tested
 * [Reconciliation.diff]. The comparison transfers only versions (a `GROUP BY max(version)` per side),
 * never payloads, so it does not load the operational databases.
 *
 * On drift it **does not auto-remediate** — automatically reloading a 10-year store of record must be
 * a deliberate operator action. Instead it records the actionable keys (missing-in-warehouse →
 * candidates for `BACKFILL`; mismatches → lag or lost update) and surfaces them via the
 * `ROLE_AUDITOR` endpoint so an operator can trigger a targeted backfill. The schedule is an off-peak
 * cron, never a fixed-rate, so reconciliation never competes with customer traffic.
 */
@ApplicationScoped
class ReconciliationJob {

    @Inject lateinit var source: ReconciliationSource

    @Inject lateinit var clock: Clock

    @Inject lateinit var warehouse: WarehouseStateReader

    @Inject lateinit var worm: WormArchive

    @Inject lateinit var domainMetrics: DomainMetrics

    private val log = Logger.getLogger(ReconciliationJob::class.java)
    private val lastRun = AtomicReference<ReconciliationResult?>(null)
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun registerLiveness() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(cron = "{openbank.analytics.reconcile.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduled() {
        runBlocking { run("scheduled") }
    }

    /** Runs one reconciliation pass, stores and returns the result. */
    suspend fun run(trigger: String): ReconciliationResult {
        val startedAt = Instant.now(clock)
        val diff = Reconciliation.diff(source.currentVersions(), warehouse.currentVersions())

        // F4: independent per-type row-count tie-out (catches whole-aggregate loss a max-version
        // comparison misses) and F5: completeness gap detection (a version missing mid-sequence is a
        // provably lost event). Both transfer only counts/versions, never payloads.
        val countDeltas = Reconciliation.countDiff(source.rowCountsByType(), warehouse.rowCountsByType())
        val countDrift = countDeltas.filterValues { !it.inSync }
        val completeness = Completeness.gapsFromVersions(warehouse.versionsByAggregate())

        // F4: a signed fingerprint of the outcome, sealed to WORM, makes the *evidence itself*
        // tamper-evident (BCBS 239 audit trail). Sealing failure must not fail the read-only check.
        val fingerprint = Reconciliation.fingerprint(diff)
        runCatching {
            worm.seal(
                IntegrityAnchor(
                    anchorId = "recon-${startedAt.toEpochMilli()}",
                    merkleRoot = fingerprint,
                    previousAnchorHash = worm.latest()?.merkleRoot,
                    recordCount = diff.checked,
                    source = "RECONCILIATION:$trigger",
                    sealedAt = Instant.now(clock),
                ),
            )
        }.onFailure { log.errorf(it, "failed to seal reconciliation evidence trigger=%s", trigger) }

        val result = ReconciliationResult(
            trigger = trigger,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
            aggregatesChecked = diff.checked.toLong(),
            driftCount = diff.driftCount.toLong(),
            missingInWarehouse = diff.missingInWarehouse,
            missingInSource = diff.missingInSource,
            versionMismatch = diff.versionMismatch,
            countDrift = countDrift,
            completenessGaps = completeness.gapCount.toLong(),
            evidenceFingerprint = fingerprint,
            status = if (diff.inSync && countDrift.isEmpty() && completeness.complete) "IN_SYNC" else "DRIFT",
        )
        lastRun.set(result)
        liveness?.recordSuccess()
        log.infof(
            "analytics reconciliation trigger=%s status=%s checked=%d drift=%d (missingWh=%d orphan=%d mismatch=%d) countDrift=%d gaps=%d fp=%s",
            trigger,
            result.status,
            result.aggregatesChecked,
            result.driftCount,
            diff.missingInWarehouse.size,
            diff.missingInSource.size,
            diff.versionMismatch.size,
            countDrift.size,
            completeness.gapCount,
            fingerprint.take(12),
        )
        return result
    }

    fun lastResult(): ReconciliationResult? = lastRun.get()

    private companion object {
        const val WORKFLOW_NAME = "analytics-reconciliation"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}

data class ReconciliationResult(
    val trigger: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val aggregatesChecked: Long,
    val driftCount: Long,
    /** OLTP has it, warehouse doesn't — candidates for a targeted BACKFILL. Capped in the response. */
    val missingInWarehouse: List<AggregateKey>,
    /** Warehouse has it, OLTP doesn't — orphans/erased to investigate. */
    val missingInSource: List<AggregateKey>,
    /** Present both sides, versions differ — lag or lost update. */
    val versionMismatch: List<AggregateKey>,
    /** Per-type row-count drift (F4): aggregate type → source/warehouse counts that disagree. */
    val countDrift: Map<String, CountDelta> = emptyMap(),
    /** Number of provably-lost events: versions missing mid-sequence in bronze (F5). */
    val completenessGaps: Long = 0,
    /** Tamper-evident fingerprint of this outcome, sealed to WORM as audit evidence (F4). */
    val evidenceFingerprint: String? = null,
    val status: String,
)
