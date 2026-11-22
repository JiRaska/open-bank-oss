// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.screening

import com.openbank.kyc.application.UnscreenedCaseRetryService
import com.openbank.kyc.application.port.out.UnscreenedBacklog
import com.openbank.kyc.application.port.out.UnscreenedCaseStore
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Schedules [UnscreenedCaseRetryService] and publishes what is still unscreened (issue #15723).
 *
 * The method MUST stay a `suspend fun` — a plain `@Scheduled` body has no Vert.x context and
 * every reactive Panache call fails with HR000068 (#2148, #2187). `UnscreenedCaseRetrySchedulerIT`
 * drives the real scheduler for that reason; a test calling this method directly would supply
 * the very context the scheduler does not.
 *
 * ### What it publishes
 *
 *  - `openbank_kyc_sanctions_unscreened_cases{service="kyc"}` — in-flight cases whose sanctions
 *    check is PENDING and was never screened. Counted from the checks, not from what the sweep
 *    can retry, so a case it has no name for (opened before the name was kept, or from an event
 *    without one) is still in the number.
 *  - `openbank_kyc_sanctions_unscreened_oldest_age_seconds{service="kyc"}` — how long the oldest
 *    of them has been open, computed at scrape time; 0 when there is none.
 *  - the ADR-0237 heartbeat `kyc-unscreened-case-retry`, recorded only when a pass completed.
 *
 * Both gauges read NaN until the first measurement, seconds after start: before that the backlog
 * is unknown, and 0 would say "nothing is unscreened". `openbank.kyc.screening-retry.enabled=false`
 * stops the retries and the heartbeat, not the measurement — a disabled sweep must not also blind
 * the number that says what it is leaving behind.
 */
@ApplicationScoped
class UnscreenedCaseRetryJob(
    private val service: UnscreenedCaseRetryService,
    private val store: UnscreenedCaseStore,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.kyc.screening-retry.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.kyc.screening-retry.min-age", defaultValue = "PT2M")
    private val minAge: Duration,
    @ConfigProperty(name = "openbank.kyc.screening-retry.batch-size", defaultValue = "50")
    private val batchSize: Int,
    private val domainMetrics: DomainMetrics,
    private val registry: Instance<MeterRegistry>,
) {

    private val log = Logger.getLogger(UnscreenedCaseRetryJob::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    /** Null until the first measurement: the backlog is then unknown, not empty. */
    @Volatile
    private var backlog: UnscreenedBacklog? = null

    /**
     * Observing [StartupEvent] is also what creates this lazy bean at boot, so the gauges and the
     * boot-seeded heartbeat exist from the first scrape. The heartbeat is registered even when
     * the sweep is disabled: a disabled control that publishes none is indistinguishable from a
     * broken one.
     */
    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        if (!registry.isResolvable) return
        val meters = registry.get()
        Gauge.builder(UNSCREENED_CASES) { backlog?.cases?.toDouble() ?: Double.NaN }
            .tag("service", METRICS_SERVICE_TAG).strongReference(true).register(meters)
        Gauge.builder(UNSCREENED_OLDEST_AGE_SECONDS) { oldestAgeSeconds() }
            .tag("service", METRICS_SERVICE_TAG).strongReference(true).register(meters)
    }

    private fun oldestAgeSeconds(): Double {
        val measured = backlog ?: return Double.NaN
        val oldest = measured.oldestOpenedAt ?: return 0.0
        return maxOf(0L, Duration.between(oldest, Instant.now(clock)).seconds).toDouble()
    }

    // TooGenericExceptionCaught: one bad tick must not kill the schedule. A failed pass records no
    // heartbeat and leaves the last measurement in place, so it shows as staleness, not as zero.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        every = "{openbank.kyc.screening-retry.interval:1m}",
        identity = "kyc-unscreened-case-retry",
        concurrentExecution = SKIP,
    )
    suspend fun retryUnscreenedCases() {
        try {
            if (enabled) {
                val now = Instant.now(clock)
                val outcome = service.retryBatch(now, now.minus(minAge), batchSize)
                if (outcome.attempted > 0) {
                    log.infof(
                        "kyc.unscreened-case.retry attempted=%d settled=%d failed=%d",
                        outcome.attempted,
                        outcome.settled,
                        outcome.failed,
                    )
                }
            }
            backlog = store.backlog()
            if (enabled) liveness?.recordSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.errorf(e, "kyc.unscreened-case.retry FAILED")
        }
    }

    companion object {
        const val UNSCREENED_CASES = "openbank.kyc.sanctions.unscreened.cases"
        const val UNSCREENED_OLDEST_AGE_SECONDS = "openbank.kyc.sanctions.unscreened.oldest.age.seconds"
        const val WORKFLOW_NAME = "kyc-unscreened-case-retry"
        private const val METRICS_SERVICE_TAG = "kyc"
        private val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
