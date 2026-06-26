// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.infrastructure.outbox

import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.statement.application.port.out.StatementOutboxRepository
import com.openbank.statement.infrastructure.kafka.KafkaStatementOutboxEventPublisher
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

/**
 * Drains the statement transactional outbox to Kafka (ADR-0050 / ADR-0049 D3).
 *
 * Extends [AbstractOutboxDispatcher] — the shared coroutine-based dispatch loop that handles
 * `listProcessable` → publish → `markSent` / `markFailed` sequencing. The `@Scheduled` tick
 * is gated by `openbank.outbox.dispatch-enabled` (off by default in `%test` profile) so the
 * scheduler never races assertions in integration tests.
 *
 * **CDI proxying constraint (ADR-0013):** `@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`
 * must be on this concrete bean's methods so the CDI proxy intercepts them — annotations on the
 * abstract superclass would be invisible to the proxy.
 */
@ApplicationScoped
class StatementOutboxDispatcher(
    private val repo: StatementOutboxRepository,
    private val publisher: KafkaStatementOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "statement-outbox-dispatcher",
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
    override suspend fun publishWithResilience(entry: OutboxEntry): Unit = publisher.publish(entry)
}
