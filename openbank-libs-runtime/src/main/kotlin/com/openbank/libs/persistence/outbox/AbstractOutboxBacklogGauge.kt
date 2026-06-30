// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared outbox **backlog** gauge (ADR-0049 consolidation; ADR-0077 / ADR-0079 metrics).
 *
 * Publishes the per-service outbox backlog (PENDING + FAILED rows) as the
 * `openbank.outbox.backlog` gauge tagged `service=<name>`. A rising backlog means the
 * service's domain events are stuck on their way to Kafka.
 *
 * Micrometer samples a gauge supplier synchronously on the Prometheus scrape (worker)
 * thread, but the backlog count is a reactive query — so a scheduled `suspend` tick
 * refreshes a cached [AtomicLong] on the right context and the gauge supplier reads that
 * cache cheaply and lock-free. This base owns that cache + the registration; the concrete
 * bean binds the service's outbox repository and carries the scheduling annotations.
 *
 * A concrete subclass binds the service's outbox repository and `DomainMetrics`:
 * ```
 * @Startup
 * @ApplicationScoped
 * class PartyOutboxBacklogGauge(
 *     private val outboxRepository: PartyOutboxRepository,
 *     metrics: DomainMetrics,
 * ) : AbstractOutboxBacklogGauge(metrics) {
 *     override val service: String = "party"
 *     override suspend fun currentBacklog(): Long = outboxRepository.countProcessable()
 *
 *     @PostConstruct
 *     fun register() = registerBacklogGauge()
 *
 *     @Scheduled(every = "10s", delayed = "10s",
 *                concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
 *     suspend fun refresh() = refreshBacklog()
 * }
 * ```
 *
 * ### CDI proxying constraint (ADR-0013)
 * The `@PostConstruct` and `@Scheduled` annotations **must** be placed on the concrete
 * `@ApplicationScoped` bean's methods — **not** on this abstract base. CDI lifecycle and
 * scheduler interception only fire for methods declared on the concrete bean that the proxy
 * wraps. The abstract class owns the logic ([registerBacklogGauge] / [refreshBacklog]); the
 * concrete bean owns the annotations and the one-line calls into them. This mirrors
 * [AbstractOutboxDispatcher].
 */
abstract class AbstractOutboxBacklogGauge {
    private lateinit var metrics: DomainMetrics
    private val cached = AtomicLong(0)

    constructor(metrics: DomainMetrics) {
        this.metrics = metrics
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime
    protected constructor()

    /** The `service` tag value for the gauge (the short service name, e.g. `"party"`). */
    protected abstract val service: String

    /** Current outbox backlog (PENDING + FAILED). Implemented by querying the service's repository. */
    protected abstract suspend fun currentBacklog(): Long

    /**
     * Register the lock-free gauge supplier. Call from the concrete bean's `@PostConstruct`.
     * The supplier reads the cached value refreshed by [refreshBacklog].
     */
    protected fun registerBacklogGauge() {
        metrics.registerOutboxBacklog(service) { cached.get() }
    }

    /**
     * Refresh the cached backlog from the repository. Call from the concrete bean's
     * `@Scheduled` `suspend` method so the reactive query runs on the right context.
     */
    protected suspend fun refreshBacklog() {
        cached.set(currentBacklog())
    }
}
