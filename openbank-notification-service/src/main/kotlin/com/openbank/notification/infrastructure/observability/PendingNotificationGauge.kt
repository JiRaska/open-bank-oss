// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** A row stuck PENDING after provider handoff may be unsent or accepted; neither is a success. */
@Startup
@ApplicationScoped
class PendingNotificationGauge(
    private val repository: NotificationRepository,
    private val registry: MeterRegistry,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private val stale = AtomicLong(UNKNOWN)
    private val staleByChannel = NotificationChannel.entries.associate { it.name to AtomicLong(UNKNOWN) }
    private val log = Logger.getLogger(PendingNotificationGauge::class.java)
    private lateinit var liveness: WorkflowLivenessRecorder

    @PostConstruct
    fun register() {
        Gauge.builder("openbank.notification.pending.stale", stale) { it.get().toDouble() }
            .tag("service", "notification")
            .description("Notifications without a terminal outcome after 15 minutes; -1 means the query failed")
            .register(registry)
        // Same count per channel: the total cannot say which adapter left a row without an outcome.
        staleByChannel.forEach { (channel, value) ->
            Gauge.builder("openbank.notification.pending.stale.by.channel", value) { it.get().toDouble() }
                .tag("service", "notification")
                .tag("channel", channel)
                .description("Stale PENDING notifications per channel; -1 means the query failed")
                .register(registry)
        }
        liveness = domainMetrics.registerWorkflowLiveness("notification-pending-observation", Duration.ofMinutes(1))
    }

    @Scheduled(every = "1m", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Suppress("TooGenericExceptionCaught") // Any unreadable count is UNKNOWN, never a false zero.
    suspend fun refresh() {
        try {
            val counts = repository.countStalePendingByChannel(Instant.now(clock).minus(STALE_AFTER)).awaitSuspending()
            staleByChannel.forEach { (channel, value) -> value.set(counts[channel] ?: 0L) }
            stale.set(counts.values.sum())
            liveness.recordSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stale.set(UNKNOWN)
            staleByChannel.values.forEach { it.set(UNKNOWN) }
            log.error("stale notification count unavailable", e)
        }
    }

    private companion object {
        const val UNKNOWN = -1L
        val STALE_AFTER: Duration = Duration.ofMinutes(15)
    }
}
