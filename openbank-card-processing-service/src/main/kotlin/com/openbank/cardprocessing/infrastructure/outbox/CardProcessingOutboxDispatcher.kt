// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.outbox

import com.openbank.cardprocessing.application.port.out.CardProcessingOutboxRepository
import com.openbank.cardprocessing.infrastructure.kafka.KafkaCardProcessingOutboxEventPublisher
import com.openbank.libs.observability.DomainMetrics
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
 * Drains the card-processing outbox to Kafka (ADR-0050).
 *
 * [dispatch] is a **`suspend fun`**. A plain `@Scheduled` method carries no Vert.x context —
 * Quarkus invokes it on a bare executor thread — so a `runBlocking { }` around a reactive Panache
 * call throws `HR000068` and the job aborts having done nothing, silently. Five schedulers, three of
 * them money-path, had never once run before that was found (#2148/#2187), and
 * `check-no-runblocking-in-scheduled.py` now enforces it.
 */
@ApplicationScoped
class CardProcessingOutboxDispatcher(
    private val repo: CardProcessingOutboxRepository,
    private val publisher: KafkaCardProcessingOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    /** Matches the gauges' label; the class-name-derived default would disagree (#5049). */
    override val service: String = "card-processing"

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "card-processing-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = VOLUME_THRESHOLD, failureRatio = FAILURE_RATIO, delay = BREAKER_DELAY_MS)
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(BATCH_TIMEOUT_MS)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = VOLUME_THRESHOLD, failureRatio = FAILURE_RATIO, delay = BREAKER_DELAY_MS)
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(PUBLISH_TIMEOUT_MS)
    override suspend fun publishWithResilience(entry: OutboxEntry) = publisher.publish(entry)

    /** Drains a batch without the [dispatchEnabled] gate. Used by integration tests. */
    internal suspend fun dispatchForTest() = dispatchScheduledBatch()

    private companion object {
        // Named rather than inline: the per-row policy is legible in one place, and detekt's
        // MagicNumber has nothing to say about an annotation argument. Values match the
        // fleet-standard outbox dispatcher settings.
        const val VOLUME_THRESHOLD = 10
        const val FAILURE_RATIO = 0.5
        const val BREAKER_DELAY_MS = 5_000L
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 200L
        const val RETRY_JITTER_MS = 100L

        /** A whole batch may take a while; a single publish must not. */
        const val BATCH_TIMEOUT_MS = 30_000L
        const val PUBLISH_TIMEOUT_MS = 3_000L
    }
}
