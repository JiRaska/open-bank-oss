// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.infrastructure.outbox

import com.openbank.cardissuance.application.port.out.CardOutboxRepository
import com.openbank.cardissuance.infrastructure.kafka.KafkaCardOutboxEventPublisher
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
 * Drains the card transactional outbox to Kafka (ADR-0049 D3 / ADR-0050).
 *
 * Extends [AbstractOutboxDispatcher] from libs: the base class owns the dispatch loop
 * ([dispatchScheduledBatch]) while this class provides the scheduling annotations, the
 * service's [CardOutboxRepository] and [KafkaCardOutboxEventPublisher] bindings, and the
 * per-row resilience policy.
 *
 * **N4 — single writer.** `concurrentExecution = SKIP` prevents in-JVM overlap. Entries are
 * processed sequentially by the base class, preserving per-aggregate ordering.
 *
 * Per-row publish failures are isolated via [publishWithResilience] so one bad row never aborts
 * the batch; repeated failures are bounded by the DEAD transition (ADR-0050 N5).
 */
@ApplicationScoped
class CardOutboxDispatcher(
    private val repo: CardOutboxRepository,
    private val publisher: KafkaCardOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "card-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(30000)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(3000)
    override suspend fun publishWithResilience(entry: OutboxEntry) = publisher.publish(entry)

    /** Directly drains a batch without the [dispatchEnabled] gate. Used in integration tests. */
    internal suspend fun dispatchForTest() = dispatchScheduledBatch()
}
