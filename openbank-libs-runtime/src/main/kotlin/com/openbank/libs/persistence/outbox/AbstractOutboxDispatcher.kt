// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import org.jboss.logging.Logger

/**
 * Shared outbox dispatch loop (ADR-0049 D3 / ADR-0050 N1).
 *
 * A concrete subclass binds the service's [OutboxRepository] and [OutboxEventPublisher] ports:
 * ```
 * @ApplicationScoped
 * class AuditOutboxDispatcher(
 *     private val repository: AuditOutboxRepository,
 *     private val publisher: AuditOutboxEventPublisher,
 * ) : AbstractOutboxDispatcher() {
 *     override val outboxRepository: OutboxRepository get() = repository
 *     override val outboxEventPublisher: OutboxEventPublisher get() = publisher
 *
 *     @Scheduled(every = "5s", identity = "audit-outbox-dispatcher")
 *     @Bulkhead(1)
 *     @CircuitBreaker(requestVolumeThreshold = 5)
 *     @Retry(maxRetries = 3)
 *     @Timeout(5000)
 *     suspend fun dispatch(): Unit = dispatchScheduledBatch()
 *
 *     @Bulkhead(1)
 *     @CircuitBreaker(requestVolumeThreshold = 5)
 *     @Retry(maxRetries = 3)
 *     @Timeout(5000)
 *     override suspend fun publishWithResilience(entry: OutboxEntry): Unit =
 *         outboxEventPublisher.publish(entry)
 * }
 * ```
 *
 * ### CDI proxying constraint (ADR-0013)
 * The `@Scheduled`, `@Bulkhead`, `@CircuitBreaker`, `@Retry`, and `@Timeout` annotations
 * **must** be placed on the concrete `@ApplicationScoped` bean's methods — **not** on the
 * abstract base-class methods. CDI interceptors only fire when the call enters through the
 * CDI proxy that wraps the concrete bean; annotating an abstract superclass method does not
 * cause the proxy to be generated around that call site. The abstract class owns the logic;
 * the concrete override owns the annotations.
 *
 * `self`-injection (`@Inject lateinit var self: T`) is intentionally **omitted** here.
 * Injecting an abstract type via CDI is unsupported (Arc rejects it at validation time).
 * Resilience interceptors must instead be triggered via CDI injection of the concrete bean
 * itself — callers of [dispatchScheduledBatch] should be annotated `@Scheduled` methods on
 * the concrete bean, which already goes through the proxy.
 *
 * ### Metrics (issue #5091 phase 1)
 * [metrics] and [service] are `open` with `null` defaults, not `abstract` — every existing
 * concrete dispatcher across the fleet compiles unchanged. A subclass opts in to
 * `openbank_outbox_dispatched_total` by overriding both:
 * ```
 * override val metrics: DomainMetrics get() = domainMetrics   // inject it in the constructor
 * override val service: String get() = "ledger"
 * ```
 * With either left `null` (the default), dispatch behaves exactly as before — no metric, no
 * behaviour change. This is deliberately NOT the same "every subclass must implement" shape as
 * [outboxRepository]/[outboxEventPublisher]: those are load-bearing to dispatch AT ALL, these two
 * are purely observational, and forcing every one of the ~30 existing dispatchers to add them in
 * the same change that introduces the mechanism would turn a scoped, verified rollout into a
 * fleet-wide breaking change reviewed all at once. Remaining services are tracked as a checklist
 * in issue #5091 — adding metrics/service to one is a two-line, low-risk follow-up once this
 * mechanism has proven itself live on the first services that opt in.
 */
abstract class AbstractOutboxDispatcher {

    protected abstract val outboxRepository: OutboxRepository
    protected abstract val outboxEventPublisher: OutboxEventPublisher
    protected open val metrics: DomainMetrics? = null
    protected open val service: String? = null

    /**
     * Core dispatch loop. Call this from the concrete subclass's `@Scheduled` method.
     *
     * Resilience annotations (`@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`) belong
     * on the concrete override of [publishWithResilience] — so CDI proxying kicks in when the
     * `@Scheduled` method on the concrete bean calls through the proxy.
     */

    protected open suspend fun dispatchScheduledBatch() {
        val m = metrics
        val svc = service
        val observer = if (m != null && svc != null) {
            OutboxDispatchObserver { entry -> m.outboxDispatched(svc, entry.eventType) }
        } else {
            OutboxDispatchObserver.NOOP
        }
        OutboxDispatch.dispatchOnce(outboxRepository, observer = observer) { entry ->
            publishWithResilience(entry)
        }
    }

    /**
     * Single-entry publish. Override in the concrete subclass and annotate with
     * `@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout` so the CDI proxy intercepts.
     *
     * Default implementation calls [OutboxEventPublisher.publish] directly (no resilience).
     */
    protected open suspend fun publishWithResilience(entry: OutboxEntry) {
        outboxEventPublisher.publish(entry)
    }

    companion object {
        val log: Logger = Logger.getLogger(AbstractOutboxDispatcher::class.java)
        const val DEFAULT_BATCH_SIZE: Int = OutboxDispatch.DEFAULT_BATCH_SIZE
    }
}
