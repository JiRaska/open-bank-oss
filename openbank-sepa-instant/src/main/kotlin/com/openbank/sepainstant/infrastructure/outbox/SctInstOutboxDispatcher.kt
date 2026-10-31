// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

@ApplicationScoped
class SctInstOutboxDispatcher(
    private val repo: SctInstOutboxRepository,
    private val publisher: OutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
    @ConfigProperty(name = "openbank.outbox.batch-size", defaultValue = "250")
    override val dispatchBatchSize: Int,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    private companion object {
        const val CIRCUIT_DELAY_MS = 5000L
        const val DISPATCH_TIMEOUT_MS = 30000L
        const val PUBLISH_TIMEOUT_MS = 3000L
    }

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "sct-inst-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = CIRCUIT_DELAY_MS)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(DISPATCH_TIMEOUT_MS)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    @Bulkhead(OutboxDispatch.SEND_CONCURRENCY)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = CIRCUIT_DELAY_MS)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(PUBLISH_TIMEOUT_MS)
    override suspend fun publishWithResilience(entry: OutboxEntry): Unit = publisher.publish(entry)
}
