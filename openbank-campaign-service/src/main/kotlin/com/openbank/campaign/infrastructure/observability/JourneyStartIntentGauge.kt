// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.observability

import com.openbank.campaign.application.usecase.JourneyStartIntentStore
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** A durable intent older than the recovery window is an unresolved admission, not success. */
@Startup
@ApplicationScoped
class JourneyStartIntentGauge(
    private val intents: JourneyStartIntentStore,
    private val registry: MeterRegistry,
    private val metrics: DomainMetrics,
) {
    private val stale = AtomicLong(UNKNOWN)
    private val log = Logger.getLogger(JourneyStartIntentGauge::class.java)
    private lateinit var liveness: WorkflowLivenessRecorder

    @PostConstruct
    fun register() {
        Gauge.builder("openbank.campaign.journey.start.intent.stale", stale) { it.get().toDouble() }
            .tag("service", "campaign")
            .description("Journey starts without committed enrolment after 15 minutes; -1 means unreadable")
            .register(registry)
        liveness = metrics.registerWorkflowLiveness("campaign-journey-start-observation", Duration.ofMinutes(1))
    }

    @Scheduled(every = "1m", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Suppress("TooGenericExceptionCaught") // An unreadable count must not look like zero unresolved intents.
    suspend fun refresh() {
        try {
            stale.set(intents.countStale())
            liveness.recordSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stale.set(UNKNOWN)
            log.error("journey start intent count unavailable", e)
        }
    }

    private companion object {
        const val UNKNOWN = -1L
    }
}
