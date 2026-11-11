// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.infrastructure.outbox

import com.openbank.ledger.infrastructure.messaging.KafkaLedgerOutboxEventPublisher
import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.ledger.infrastructure.schedule.ScheduleSwitch
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.quarkus.runtime.configuration.DurationConverter
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.Duration

/**
 * Drains the ledger transactional outbox to Kafka (ADR-0049 D3 / ADR-0050).
 *
 * Extends [AbstractOutboxDispatcher] from openbank-libs; all dispatch logic is inherited.
 * Resilience annotations live here on the concrete CDI bean — CDI interceptors only fire when
 * the call enters through the proxy that wraps this bean (ADR-0013).
 *
 * The repository claims one head per aggregate and issues a token for each claim. The token
 * protects acknowledgements after an expired claim is replaced by another dispatcher instance.
 */
@ApplicationScoped
class LedgerOutboxDispatcher(
    private val repo: LedgerOutboxRepositoryImpl,
    private val publisher: KafkaLedgerOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
    metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.outbox.poll-interval", defaultValue = "5s")
    pollInterval: String,
) : AbstractOutboxDispatcher(metrics) {

    // The pension ledger supplies `off` to the same property the scheduler reads.
    override val dispatchPollInterval: Duration = if (ScheduleSwitch.isOffValue(pollInterval)) {
        super.dispatchPollInterval
    } else {
        DurationConverter.parseDuration(pollInterval)
    }

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

    @Bulkhead(OutboxDispatch.SEND_CONCURRENCY)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(3000)
    override suspend fun publishWithResilience(entry: OutboxEntry) = publisher.publish(entry)
}
