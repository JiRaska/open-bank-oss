// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.notification.application.port.out.NotificationOutboxRepository
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
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
 */
@ApplicationScoped
class NotificationOutboxDeadLetterJanitorJob {

    @Inject
    lateinit var outboxRepo: NotificationOutboxRepository

    @Inject
    lateinit var clock: Clock

    // TooGenericExceptionCaught: a retention janitor must not surface as a Quarkus Scheduler
    // failure — ANY fault is logged and tomorrow's tick purges the same rows again.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(cron = "0 0 2 * * ?", identity = "notification-outbox-dead-letter-janitor", concurrentExecution = SKIP)
    suspend fun purgeDeadLetters() {
        val threshold = Instant.now(clock).minus(RETENTION_DAYS, ChronoUnit.DAYS)
        try {
            val count = buildPurgePipeline(threshold).awaitSuspending()
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
    }
}
