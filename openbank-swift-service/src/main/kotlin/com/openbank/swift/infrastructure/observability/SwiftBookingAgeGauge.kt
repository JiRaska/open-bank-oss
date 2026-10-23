// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.swift.application.port.out.SwiftBookingAgeSnapshot
import com.openbank.swift.application.port.out.SwiftBookingQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Tracked pending age is a lower bound; unknown coverage is reported separately. */
@Startup
@ApplicationScoped
class SwiftBookingAgeGauge(
    private val query: SwiftBookingQuery,
    private val registry: MeterRegistry?,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    @Inject
    constructor(
        query: SwiftBookingQuery,
        registries: Instance<MeterRegistry>,
        clock: Clock,
        domainMetrics: DomainMetrics,
    ) : this(query, if (registries.isResolvable) registries.get() else null, clock, domainMetrics)

    private val snapshot = AtomicReference<SwiftBookingAgeSnapshot?>()
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        Gauge.builder(AGE_METRIC, this) { it.ageSeconds() }
            .tag("rail", "SWIFT").strongReference(true).register(r)
        Gauge.builder(UNKNOWN_METRIC, this) { it.snapshot.get()?.unknownLegacyCount?.toDouble() ?: Double.NaN }
            .tag("rail", "SWIFT").strongReference(true).register(r)
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness("swift-booking-age", Duration.ofSeconds(REFRESH_SECONDS))
    }

    @Scheduled(every = "30s", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() {
        val current = query.bookingAgeSnapshot()
        snapshot.set(current)
        liveness?.recordSuccess()
    }

    private fun ageSeconds(): Double {
        val known = snapshot.get() ?: return Double.NaN
        val oldest = known.oldestDueAt ?: return 0.0
        return maxOf(0L, Duration.between(oldest, Instant.now(clock)).seconds).toDouble()
    }

    companion object {
        const val AGE_METRIC = "openbank.payment.booking.tracked.pending.oldest.age.seconds"
        const val UNKNOWN_METRIC = "openbank.payment.booking.unknown.coverage"
        private const val REFRESH_SECONDS = 30L
    }
}
