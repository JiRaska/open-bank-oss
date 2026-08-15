// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.outbox

import com.openbank.fraud.application.port.out.FraudOutboxRepository
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

/**
 * Drains the fraud-hold transactional outbox (ADR-0050). Same shape as
 * `AccountOutboxDispatcher` — see its KDoc for the cross-pod claim reasoning (#1201).
 */
@ApplicationScoped
class FraudOutboxDispatcher(
    private val repo: FraudOutboxRepository,
    private val publisher: OutboxEventPublisher,
    // Defaults to false (house convention): a service that forgets to flip this ships an outbox
    // that fills forever with attempt_count staying 0 and no error anywhere.
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {
    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "fraud-outbox-dispatcher",
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
