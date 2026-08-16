// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import jakarta.inject.Inject
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
 */
abstract class AbstractOutboxDispatcher {

    protected abstract val outboxRepository: OutboxRepository
    protected abstract val outboxEventPublisher: OutboxEventPublisher

    /**
     * `service` tag for [DomainMetrics.outboxDispatched] / `.outboxDead` (#5049). Defaults to a
     * kebab-case derivation of the concrete class's simple name — `PartyOutboxDispatcher`
     * becomes `party` — which was verified (2026-08-16) to reproduce the `service` string every
     * one of the 31 sibling [AbstractOutboxBacklogGauge] subclasses already hardcodes, so the
     * outbox-dispatch and outbox-backlog panels of the same dashboard share one label
     * vocabulary. Three dispatchers whose derivation would silently disagree with their own
     * gauge override it explicitly: `CardOutboxDispatcher` (`card-issuance`, not `card`),
     * `TppOutboxDispatcher` (`tpp-registry`, not `tpp`), `DomesticPaymentOutboxDispatcher`
     * (`domestic`, not `domestic-payment`). A new dispatcher whose class name does not already
     * match its own gauge's `service` must override this the same way.
     */
    protected open val service: String get() = deriveServiceName(this::class.java.simpleName)

    /**
     * Metrics facade, deliberately **field**-injected rather than threaded through the
     * constructor (contrast [AbstractOutboxBacklogGauge], whose concrete subclasses already
     * accept a `metrics: DomainMetrics` constructor parameter) so this change touches none of
     * the ~34 concrete dispatchers' constructors (#5049).
     *
     * `null` means either "not CDI-managed" — every dispatcher unit test in the fleet
     * (`AbstractOutboxDispatcherTest` included) constructs its dispatcher directly with `class
     * Foo(...) : AbstractOutboxDispatcher()`, bypassing the container entirely — or a
     * not-yet-resolved bean. [dispatchScheduledBatch] treats either as "skip metrics, never
     * crash", the same graceful-degradation contract [DomainMetrics.reg] uses for a missing
     * `MeterRegistry`. Visible to this package's tests via direct field assignment (protected).
     */
    @Inject
    protected var metrics: DomainMetrics? = null

    /**
     * Core dispatch loop. Call this from the concrete subclass's `@Scheduled` method.
     *
     * Resilience annotations (`@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`) belong
     * on the concrete override of [publishWithResilience] — so CDI proxying kicks in when the
     * `@Scheduled` method on the concrete bean calls through the proxy.
     */

    protected open suspend fun dispatchScheduledBatch() {
        val result = OutboxDispatch.dispatchOnce(outboxRepository) { entry ->
            publishWithResilience(entry)
        }
        val m = metrics ?: return
        for (outcome in result.outcomes) {
            when (outcome) {
                is OutboxDispatchOutcome.Dispatched -> m.outboxDispatched(service, outcome.entry.eventType)
                is OutboxDispatchOutcome.Failed -> if (outcome.terminal) m.outboxDead(service)
            }
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

        /** `FooBarOutboxDispatcher` -> `foo-bar`. See [service] for why this exists. */
        internal fun deriveServiceName(simpleClassName: String): String = simpleClassName
            .removeSuffix("OutboxDispatcher")
            .replace(Regex("(?<!^)(?=[A-Z])"), "-")
            .lowercase()
    }
}
