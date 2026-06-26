// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.infrastructure.outbox

import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.sdd.application.port.out.SddOutboxRepository
import com.openbank.sdd.infrastructure.kafka.KafkaSddOutboxEventPublisher
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout

/**
 * Drains the SDD transactional outbox to Kafka (ADR-0050 / ADR-0049 D3).
 *
 * Extends [AbstractOutboxDispatcher] — the shared dispatch loop lives in libs; this class owns the
 * `@Scheduled` trigger, the CDI-proxied resilience annotations, and the guard that keeps the
 * dispatcher dormant until `openbank.outbox.dispatch-enabled=true` is flipped in GitOps.
 *
 * **CDI proxying note (ADR-0013).** `@Scheduled`, `@Bulkhead`, `@CircuitBreaker`, `@Retry`, and
 * `@Timeout` **must** appear on the concrete bean's methods, not on abstract base-class methods.
 * CDI interceptors only fire through the proxy that wraps the concrete `@ApplicationScoped` bean.
 *
 * **N4 — single writer.** `concurrentExecution = SKIP` prevents in-JVM overlap; the SDD Deployment
 * is pinned to `replicas: 1`. Together they guarantee at most one dispatcher claims a row.
 * Entries are processed sequentially by [AbstractOutboxDispatcher], preserving per-aggregate order.
 */
@ApplicationScoped
class SddOutboxDispatcher(
    private val repo: SddOutboxRepository,
    private val publisher: KafkaSddOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val dispatchEnabled: Boolean,
) : AbstractOutboxDispatcher() {

    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "sdd-outbox-dispatcher",
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
