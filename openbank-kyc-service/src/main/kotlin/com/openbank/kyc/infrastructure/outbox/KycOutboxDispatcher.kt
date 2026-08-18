// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.outbox

import com.openbank.kyc.application.port.out.KycOutboxRepository
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
 * Drains the kyc transactional outbox (ADR-0050 / ADR-0049 D3).
 *
 * **N4 — cross-pod row claim (#1201).** `concurrentExecution = SKIP` only prevents in-JVM
 * overlap; it does not stop two pods from both running this scheduled method. `replicas: 1` is
 * steady-state only — an Argo Rollouts canary window runs the old and new pod simultaneously
 * for the whole rollout duration, and both dispatch on their own tick. `KycOutboxRepositoryImpl`
 * therefore implements [OutboxRepository.claimProcessable] as an atomic `FOR UPDATE SKIP LOCKED`
 * claim, not the unclaimed-peek default, so two concurrently running pods can never both select
 * and publish the same row.
 */
@ApplicationScoped
class KycOutboxDispatcher(
    private val repo: KycOutboxRepository,
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
        identity = "kyc-outbox-dispatcher",
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
