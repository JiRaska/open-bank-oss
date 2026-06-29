// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.notification.application.port.out.NotificationOutboxRepository
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import io.smallrye.mutiny.Uni
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
 * The job is fire-and-forget (subscribe().with): a failure is logged but does not surface as a
 * Quarkus Scheduler failure, matching the pattern established by [DeviceTokenSweepJob]. The job
 * runs at 02:00 server time (1 hour before the device-token sweep) so both jobs never overlap.
 */
@ApplicationScoped
class NotificationOutboxDeadLetterJanitorJob {

    @Inject
    lateinit var outboxRepo: NotificationOutboxRepository

    @Inject
    lateinit var clock: Clock

    @Scheduled(cron = "0 0 2 * * ?", identity = "notification-outbox-dead-letter-janitor", concurrentExecution = SKIP)
    fun purgeDeadLetters() {
        val threshold = Instant.now(clock).minus(RETENTION_DAYS, ChronoUnit.DAYS)
        buildPurgePipeline(threshold)
            .subscribe().with(
                { count ->
                    if (count > 0) {
                        Log.infof(
                            "notification.outbox.dead_letter.purged count=%d threshold=%s",
                            count,
                            threshold,
                        )
                    }
                },
                { err -> Log.errorf(err, "notification.outbox.dead_letter.purge FAILED threshold=%s", threshold) },
            )
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
