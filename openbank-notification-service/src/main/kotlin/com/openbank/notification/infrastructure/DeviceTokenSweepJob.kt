// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Nightly sweep that retires push device tokens not refreshed within 90 days (ADR-0135).
 * A customer who hasn't opened the app in 90 days will no longer receive fan-out deliveries
 * until they re-register on next launch — this reduces delivery noise and limits the blast
 * radius of a token database compromise.
 *
 * A failure is caught and logged rather than propagated, so it does not surface as a Quarkus
 * Scheduler failure (which would create alert noise for a non-critical job).
 *
 * The method MUST stay a `suspend fun`. It used to be a plain method that `subscribe()`d the
 * pipeline, which meant it ran on a bare `executor-thread` with no Vert.x context and
 * [DeviceTokenRepository.sweepStale]'s `Panache.withTransaction` threw `HR000068` on every single
 * firing — so no stale token was ever retired (#2913 fleet sweep). Subscribing rather than
 * awaiting does not help: the subscription still starts on the scheduler's thread
 * (`rules.yaml: scheduled_methods`).
 */
@ApplicationScoped
@Startup
class DeviceTokenSweepJob {

    @Inject
    lateinit var repo: DeviceTokenRepository

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Registers the boot-seeded ADR-0237 heartbeat before the first nightly sweep. The sweep
     * intentionally catches its failures, so only a completed repository call may record success.
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    // TooGenericExceptionCaught: a non-critical nightly sweep must not surface as a Quarkus
    // Scheduler failure — ANY fault is logged and tomorrow's tick sweeps the same rows again.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        cron = "{openbank.notification.device-token-stale-sweep.cron:0 0 3 * * ?}",
        identity = "device-token-stale-sweep",
        concurrentExecution = SKIP,
    )
    suspend fun sweepStaleTokens() {
        val threshold = Instant.now(clock).minus(STALE_DAYS, ChronoUnit.DAYS)
        try {
            val count = repo.sweepStale(threshold).awaitSuspending()
            liveness?.recordSuccess()
            if (count > 0) Log.infof("Swept %d stale device tokens (threshold %s)", count, threshold)
        } catch (err: Exception) {
            Log.errorf(err, "Failed to sweep stale device tokens")
        }
    }

    companion object {
        private const val STALE_DAYS = 90L
        private const val WORKFLOW_NAME = "device-token-stale-sweep"
        private val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
