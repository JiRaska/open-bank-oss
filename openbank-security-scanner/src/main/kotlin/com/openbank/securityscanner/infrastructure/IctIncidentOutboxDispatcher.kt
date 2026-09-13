// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.securityscanner.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.security.application.port.out.IctIncidentOutboxRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

@ApplicationScoped
class IctIncidentOutboxDispatcher(
    private val repository: IctIncidentOutboxRepository,
    private val publisher: OutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    override val service: String = "security-scanner"
    override val outboxRepository: OutboxRepository get() = repository
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "ict-incident-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(
        requestVolumeThreshold = CIRCUIT_BREAKER_VOLUME,
        failureRatio = CIRCUIT_BREAKER_FAILURE_RATIO,
        delay = CIRCUIT_BREAKER_DELAY_MS,
    )
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(DISPATCH_TIMEOUT_MS)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    @Bulkhead(1)
    @CircuitBreaker(
        requestVolumeThreshold = CIRCUIT_BREAKER_VOLUME,
        failureRatio = CIRCUIT_BREAKER_FAILURE_RATIO,
        delay = CIRCUIT_BREAKER_DELAY_MS,
    )
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(PUBLISH_TIMEOUT_MS)
    override suspend fun publishWithResilience(entry: OutboxEntry) = publisher.publish(entry)

    private companion object {
        const val CIRCUIT_BREAKER_VOLUME = 10
        const val CIRCUIT_BREAKER_FAILURE_RATIO = 0.5
        const val CIRCUIT_BREAKER_DELAY_MS = 5_000L
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 200L
        const val RETRY_JITTER_MS = 100L
        const val DISPATCH_TIMEOUT_MS = 30_000L
        const val PUBLISH_TIMEOUT_MS = 3_000L
    }
}
