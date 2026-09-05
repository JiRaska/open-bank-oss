// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.loyalty.application.port.out.LoyaltyOutboxRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

/**
 * Drains the Lístek outbox (ADR-0050), same shape as `EngagementOutboxDispatcher`.
 *
 * [dispatch] is a `suspend fun`, not a plain one wrapping `runBlocking`. A plain `@Scheduled`
 * method carries no Vert.x context, so a reactive Panache call inside `runBlocking` throws
 * HR000068 and the job aborts silently having done nothing — five schedulers in this fleet, three
 * of them money-path, had never run. `check-no-runblocking-in-scheduled.py` enforces it.
 *
 * `openbank.outbox.dispatch-enabled` defaults to false by house convention, and this service sets
 * it true in `application.yaml`: a service that forgets ships an outbox that fills forever with
 * `attempt_count` stuck at 0 and no error anywhere.
 */
@ApplicationScoped
class LoyaltyOutboxDispatcher(
    private val repo: LoyaltyOutboxRepository,
    private val publisher: OutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "loyalty-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(
        requestVolumeThreshold = FT_VOLUME_THRESHOLD,
        failureRatio = FT_FAILURE_RATIO,
        delay = FT_CB_DELAY_MS,
    )
    @Retry(maxRetries = FT_MAX_RETRIES, delay = FT_RETRY_DELAY_MS, jitter = FT_RETRY_JITTER_MS)
    @Timeout(FT_BATCH_TIMEOUT_MS)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    @Bulkhead(1)
    @CircuitBreaker(
        requestVolumeThreshold = FT_VOLUME_THRESHOLD,
        failureRatio = FT_FAILURE_RATIO,
        delay = FT_CB_DELAY_MS,
    )
    @Retry(maxRetries = FT_MAX_RETRIES, delay = FT_RETRY_DELAY_MS, jitter = FT_RETRY_JITTER_MS)
    @Timeout(FT_PUBLISH_TIMEOUT_MS)
    override suspend fun publishWithResilience(entry: OutboxEntry): Unit = publisher.publish(entry)

    companion object {
        // Fault-tolerance annotation args must be compile-time constants; named rather than inline
        // so detekt's MagicNumber does not fire on a fleet-standard set of values.
        private const val FT_VOLUME_THRESHOLD = 10
        private const val FT_FAILURE_RATIO = 0.5
        private const val FT_CB_DELAY_MS = 5000L
        private const val FT_MAX_RETRIES = 2
        private const val FT_RETRY_DELAY_MS = 200L
        private const val FT_RETRY_JITTER_MS = 100L
        private const val FT_BATCH_TIMEOUT_MS = 30000L
        private const val FT_PUBLISH_TIMEOUT_MS = 3000L
    }
}
