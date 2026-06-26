// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.interest.infrastructure.outbox

import com.openbank.interest.application.port.out.InterestOutboxRepository
import com.openbank.interest.infrastructure.kafka.KafkaInterestOutboxEventPublisher
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
 * Drains the interest transactional outbox to Kafka (ADR-0050 / ADR-0049 D3).
 *
 * Extends [AbstractOutboxDispatcher] for the shared dispatch loop. The [dispatch] method carries
 * the `@Scheduled` annotation so the CDI proxy intercepts on the concrete bean (ADR-0013). The
 * `dispatchEnabled` guard lets GitOps enable draining per-environment without a redeploy.
 *
 * **N4 — single writer.** `concurrentExecution = SKIP` prevents in-JVM overlap; the interest
 * Deployment is pinned to `replicas: 1`. Resilience policies ([CircuitBreaker], [Retry],
 * [Timeout]) are applied on [publishWithResilience], which is called as a cross-bean CDI call
 * through the proxy.
 */
@ApplicationScoped
class InterestOutboxDispatcher(
    private val repo: InterestOutboxRepository,
    private val publisher: KafkaInterestOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "interest-outbox-dispatcher",
    )
    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(30000)
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }

    /** Exposed for ITs: drives one dispatch cycle without the `@Scheduled` / resilience annotations. */
    public override suspend fun dispatchScheduledBatch() = super.dispatchScheduledBatch()

    @Bulkhead(1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100)
    @Timeout(3000)
    override suspend fun publishWithResilience(entry: OutboxEntry): Unit = publisher.publish(entry)
}
