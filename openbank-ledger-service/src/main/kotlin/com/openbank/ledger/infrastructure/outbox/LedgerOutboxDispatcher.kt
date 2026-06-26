// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.ledger.infrastructure.outbox

import com.openbank.ledger.infrastructure.messaging.KafkaLedgerOutboxEventPublisher
import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
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
 * Drains the ledger transactional outbox to Kafka (ADR-0049 D3 / ADR-0050).
 *
 * Extends [AbstractOutboxDispatcher] from openbank-libs; all dispatch logic is inherited.
 * Resilience annotations live here on the concrete CDI bean — CDI interceptors only fire when
 * the call enters through the proxy that wraps this bean (ADR-0013).
 *
 * **N4 — single writer.** `concurrentExecution = SKIP` prevents in-JVM overlap, and the ledger
 * deployment is pinned to `replicas: 1`; together they guarantee exactly one dispatcher claims a
 * row. A `FOR UPDATE SKIP LOCKED` claim is the tracked refinement for any future multi-writer
 * topology.
 */
@ApplicationScoped
class LedgerOutboxDispatcher(
    private val repo: LedgerOutboxRepositoryImpl,
    private val publisher: KafkaLedgerOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "ledger-outbox-dispatcher",
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
}
