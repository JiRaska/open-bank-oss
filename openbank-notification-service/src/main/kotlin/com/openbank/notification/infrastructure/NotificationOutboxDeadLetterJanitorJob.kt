// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.notification.application.port.out.NotificationOutboxRepository
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Nightly janitor that deletes DEAD outbox rows older than [RETENTION_DAYS] days (ADR-0050 N5).
 *
 * DEAD rows are terminal — they will never be retried — but without a purge they accumulate
 * indefinitely. A 30-day retention window gives operators enough history for post-incident analysis
 * while keeping the table size bounded.
 *
 * A failure is caught and logged rather than propagated, so it does not surface as a Quarkus
 * Scheduler failure, matching the pattern established by [DeviceTokenSweepJob]. The job runs at
 * 02:00 server time (1 hour before the device-token sweep) so both jobs never overlap.
 *
 * The method MUST stay a `suspend fun`. It used to be a plain method that `subscribe()`d the
 * pipeline, so it ran on a bare `executor-thread` with no Vert.x context and the
 * `Panache.withTransaction` behind [NotificationOutboxRepository.purgeDeadBefore] threw
 * `HR000068` on every firing — no DEAD row was ever purged (#2913 fleet sweep).
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * That #2913 defect is the exact reason this heartbeat exists: the job ran, threw `HR000068` into
 * a swallowing `catch`, purged nothing, and looked identical from the outside to a night with no
 * DEAD rows to purge. Nothing escaped, no metric moved, and a count of 0 is the healthy case too.
 * [DomainMetrics.registerWorkflowLiveness] publishes the last-success age so the ADR-0237
 * staleness rule and `openbank-control-liveness-sentinel` can distinguish the two.
 *
 * [WorkflowLivenessRecorder.recordSuccess] is called only after the purge pipeline actually
 * returned — never in the `catch`, where a heartbeat would assert precisely the thing it exists to
 * disprove. Registration hangs off [StartupEvent] rather than `@PostConstruct` because
 * `@ApplicationScoped` is lazy: a `@PostConstruct` would first run when the cron first fires, up
 * to a day after boot, leaving the gauge absent for that whole window — and absent is not the same
 * signal as stale.
 */
@ApplicationScoped
class NotificationOutboxDeadLetterJanitorJob {

    @Inject
    lateinit var outboxRepo: NotificationOutboxRepository

    @Inject
    lateinit var clock: Clock

    // Field injection, not a constructor parameter: detekt's LongParameterList fires AT the
    // threshold, and the fleet convention for adding a metrics port is @Inject.
    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    // TooGenericExceptionCaught: a retention janitor must not surface as a Quarkus Scheduler
    // failure — ANY fault is logged and tomorrow's tick purges the same rows again.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(cron = "0 0 2 * * ?", identity = "notification-outbox-dead-letter-janitor", concurrentExecution = SKIP)
    suspend fun purgeDeadLetters() {
        val threshold = Instant.now(clock).minus(RETENTION_DAYS, ChronoUnit.DAYS)
        try {
            val count = buildPurgePipeline(threshold).awaitSuspending()
            // Recorded for a zero-row run too: an empty purge IS a successful purge, and
            // withholding the heartbeat on a quiet night would make a healthy job read as stale.
            liveness?.recordSuccess()
            if (count > 0) {
                Log.infof("notification.outbox.dead_letter.purged count=%d threshold=%s", count, threshold)
            }
        } catch (err: Exception) {
            Log.errorf(err, "notification.outbox.dead_letter.purge FAILED threshold=%s", threshold)
        }
    }

    /**
     * Exposed as `internal` so the unit test can call the pipeline directly without going through
     * the scheduler wiring. The scheduler entry-point [purgeDeadLetters] is fire-and-forget
     * (subscribe + callback), making it hard to assert on from a test; exposing the [Uni] lets the
     * test await the pipeline result synchronously with `await().indefinitely()`.
     */
    internal fun buildPurgePipeline(threshold: Instant): Uni<Long> = outboxRepo.purgeDeadBefore(threshold)

    companion object {
        private const val RETENTION_DAYS = 30L
        private const val WORKFLOW_NAME = "notification-outbox-dead-letter-janitor"
        private val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
