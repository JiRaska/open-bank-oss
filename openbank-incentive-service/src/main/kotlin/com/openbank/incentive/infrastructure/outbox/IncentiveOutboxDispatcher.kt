// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.outbox

import com.openbank.incentive.infrastructure.messaging.KafkaIncentiveOutboxEventPublisher
import com.openbank.incentive.infrastructure.persistence.OutboxEntities
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.Duration
import java.time.Instant

@ApplicationScoped
class IncentiveOutboxDispatcher(
    private val repository: OutboxEntities,
    private val publisher: KafkaIncentiveOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    override val outboxRepository: OutboxRepository get() = repository
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher
    override val service: String = "incentive"

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "incentive-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(DISPATCH_TIMEOUT_MILLIS)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchClaimedBatch()
    }

    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(PUBLISH_TIMEOUT_MILLIS)
    override suspend fun publishWithResilience(entry: OutboxEntry) = publisher.publish(entry)

    internal suspend fun dispatchForTest() = dispatchClaimedBatch()

    @Suppress("TooGenericExceptionCaught")
    private suspend fun dispatchClaimedBatch() {
        repository.claimWithToken(BATCH_SIZE, STALE_CLAIM_AFTER).forEach { claimed ->
            try {
                publishWithResilience(claimed.entry)
                if (repository.markSentClaimed(claimed, Instant.now())) {
                    metrics.outboxDispatched(service, claimed.entry.eventType)
                }
            } catch (failure: Exception) {
                if (OutboxDispatch.isTransportUnavailable(failure)) return
                val status = repository.markFailedClaimed(
                    claimed,
                    failure.message ?: failure::class.java.simpleName,
                    Instant.now(),
                )
                if (status == OutboxStatus.DEAD) metrics.outboxDead(service)
            }
        }
    }

    private companion object {
        const val DISPATCH_TIMEOUT_MILLIS = 30_000L
        const val PUBLISH_TIMEOUT_MILLIS = 3_000L
        const val BATCH_SIZE = 25
        val STALE_CLAIM_AFTER: Duration = Duration.ofMinutes(2)
    }
}
