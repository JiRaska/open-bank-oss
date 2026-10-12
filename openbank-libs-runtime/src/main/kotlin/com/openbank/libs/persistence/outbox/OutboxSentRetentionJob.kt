// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fleet-wide retention of SENT outbox rows (ADR-0327 D8, extended to v1 outboxes by ADR-0329).
 *
 * One bean, shipped by libs-runtime and therefore present in every service, that discovers every
 * [SentOutboxRetention] bean in the application — every kernel [OutboxRepositoryV2] and every v1
 * repository that opted in — and purges its SENT rows older than `sent-days` in bounded batches.
 * A service with no outbox has no targets and the job is a no-op that registers no liveness gauge.
 *
 * Why one shared job rather than a method on [AbstractOutboxDispatcher]: `@Scheduled` only fires
 * on the concrete bean (ADR-0013), so a base-class job would have needed a new annotated method in
 * all 41 dispatchers — 41 copies to keep in step, and a new dispatcher that forgot it would retain
 * forever with nothing to notice. Here the opt-in is the repository's type, which
 * `check-outbox-sent-retention.py` enforces per module.
 *
 * ## Configuration (`openbank.outbox.retention.*`)
 * - `enabled` (default `true`) — the only opt-out, and an explicit one.
 * - `sent-days` (default 7) — the ADR-0327 D8 contract window. A service that reads its own SENT
 *   rows (case-coordinator's `CaseThreadProjection`) must not set it below that reader's need.
 * - `batch-size` (5 000), `max-batches` (200) — bound one run per outbox.
 * - `cron` (`0 17 3 * * ?`) — nightly, off the hour so it does not stack on other 03:00 jobs.
 *
 * ## Failure and liveness (ADR-0237)
 * Each target is isolated: one failing outbox increments `openbank_outbox_purge_failed_total` and
 * the rest still run. The liveness heartbeat is recorded only when EVERY target succeeded — a run
 * that purged nothing because every delete threw must not read as a quiet night (the #2913 shape).
 * Registration hangs off [StartupEvent], not first use, because `@ApplicationScoped` is lazy and an
 * absent gauge is a different signal from a stale one.
 *
 * Runs on every replica; that is safe. Each delete is idempotent and bounded, so two pods racing
 * the same night delete disjoint-or-already-gone rows and the second one finds a short batch.
 */
@ApplicationScoped
class OutboxSentRetentionJob(
    // Constructor parameters with NO Kotlin default: a default would make Arc build the bean through
    // the synthetic constructor and the @ConfigProperty would never apply (configproperty-kotlin-defaults).
    @ConfigProperty(name = "openbank.outbox.retention.enabled", defaultValue = "true")
    internal val enabled: Boolean,
    @ConfigProperty(name = "openbank.outbox.retention.sent-days", defaultValue = "7")
    internal val sentDays: Long,
    @ConfigProperty(name = "openbank.outbox.retention.batch-size", defaultValue = "5000")
    internal val batchSize: Int,
    @ConfigProperty(name = "openbank.outbox.retention.max-batches", defaultValue = "200")
    internal val maxBatches: Int,
) {

    @Inject
    @Any
    lateinit var targets: Instance<SentOutboxRetention>

    @Inject
    lateinit var metrics: DomainMetrics

    @Inject
    lateinit var clock: Clock

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (!enabled) {
            log.warnf(
                "outbox.retention DISABLED by openbank.outbox.retention.enabled=false; SENT rows are kept forever",
            )
            return
        }
        require(sentDays > 0) { "openbank.outbox.retention.sent-days must be positive, was $sentDays" }
        require(batchSize > 0) { "openbank.outbox.retention.batch-size must be positive, was $batchSize" }
        require(maxBatches > 0) { "openbank.outbox.retention.max-batches must be positive, was $maxBatches" }
        if (targets.stream().anyMatch { !it.sentRetentionExempt }) {
            liveness = metrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
            targets.forEach { target ->
                if (!target.sentRetentionExempt) {
                    metrics.outboxPurgeCapReached(target.retentionLabel, reached = false)
                }
            }
        }
    }

    @Scheduled(
        cron = "\${openbank.outbox.retention.cron:0 17 3 * * ?}",
        identity = "openbank-outbox-sent-retention",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun purge() {
        if (!enabled) return
        purgeAll(Instant.now(clock))
    }

    /**
     * One run over every target with a single cut-off. Returns rows deleted per target label;
     * a failed target is absent from the map. `internal` for the unit test — the scheduler path is
     * proven separately by a service IT driving the real cron.
     */
    // TooGenericExceptionCaught: one outbox's failure must not starve the others or surface as a
    // scheduler failure; it is counted and logged, and tomorrow's run retries the same rows.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun purgeAll(now: Instant): Map<String, Long> {
        val retention = Duration.ofDays(sentDays)
        val purged = linkedMapOf<String, Long>()
        var allSucceeded = true
        for (target in targets) {
            if (target.sentRetentionExempt) continue
            val label = target.retentionLabel
            try {
                val count = OutboxRetention.purgeSentUntilShort(target, retention, batchSize, maxBatches, now)
                purged[label] = count
                metrics.outboxPurged(label, count)
                val capReached = count == batchSize.toLong() * maxBatches
                metrics.outboxPurgeCapReached(label, reached = capReached)
                if (capReached) {
                    log.warnf(
                        "outbox.retention.cap_reached service=%s batches=%d batch_size=%d; older SENT rows may remain",
                        label,
                        maxBatches,
                        batchSize,
                    )
                }
                if (count >
                    0
                ) {
                    log.infof("outbox.retention.purged service=%s count=%d older_than=%s", label, count, retention)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                allSucceeded = false
                metrics.outboxPurgeFailed(label)
                log.errorf(e, "outbox.retention FAILED service=%s older_than=%s", label, retention)
            }
        }
        if (allSucceeded) liveness?.recordSuccess()
        return purged
    }

    companion object {
        private val log: Logger = Logger.getLogger(OutboxSentRetentionJob::class.java)
        const val WORKFLOW_NAME: String = "outbox-sent-retention"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
