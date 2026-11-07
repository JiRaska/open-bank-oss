// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.sepa.application.port.out.SepaRefundQuery
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

/** Refund age only: independent of payment creation, outbound booking and clearing clocks. */
@Startup
@ApplicationScoped
class SepaRefundUnsettledAgeGauge(
    private val repository: SepaRefundQuery,
    private val registry: MeterRegistry?,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    @Inject
    constructor(
        repository: SepaRefundQuery,
        registryInstance: Instance<MeterRegistry>,
        clock: Clock,
        domainMetrics: DomainMetrics,
    ) : this(
        repository,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        clock,
        domainMetrics,
    )

    // null snapshot = never successfully queried. Snapshot(null) = successfully known empty.
    private data class Snapshot(val oldest: Instant?)

    private val snapshot = AtomicReference<Snapshot?>()
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        Gauge.builder(METRIC, this) { it.ageSeconds() }
            .tag("rail", "SEPA")
            .tag("operation", "refund")
            .strongReference(true)
            .register(r)
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(
            "sepa-refund-unsettled-age",
            Duration.ofSeconds(REFRESH_SECONDS),
        )
    }

    @Scheduled(every = "30s", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refresh() {
        // Query failure propagates: do not overwrite a known oldest timestamp with fabricated zero.
        val oldest = repository.oldestUnresolvedReturnAt()
        snapshot.set(Snapshot(oldest))
        liveness?.recordSuccess()
    }

    private fun ageSeconds(): Double {
        val known = snapshot.get() ?: return Double.NaN
        val oldest = known.oldest ?: return 0.0
        // Cache the timestamp, not an age: elapsed age continues increasing if refresh later fails.
        return maxOf(0L, Duration.between(oldest, Instant.now(clock)).seconds).toDouble()
    }

    companion object {
        const val METRIC = "openbank.payment.refund.unsettled.oldest.age.seconds"
        private const val REFRESH_SECONDS = 30L
    }
}
