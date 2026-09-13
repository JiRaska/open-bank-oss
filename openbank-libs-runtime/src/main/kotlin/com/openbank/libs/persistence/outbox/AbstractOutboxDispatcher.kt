// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import org.jboss.logging.Logger

/**
 * Shared outbox dispatch loop (ADR-0049 D3 / ADR-0050 N1).
 *
 * A concrete subclass binds the service's [OutboxRepository] and [OutboxEventPublisher] ports
 * and threads `metrics: DomainMetrics` through to the super constructor (mirrors
 * [AbstractOutboxBacklogGauge], #5128 finding 2):
 * ```
 * @ApplicationScoped
 * class AuditOutboxDispatcher(
 *     private val repository: AuditOutboxRepository,
 *     private val publisher: AuditOutboxEventPublisher,
 *     metrics: DomainMetrics,
 * ) : AbstractOutboxDispatcher(metrics) {
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
     * becomes `party` — chosen (2026-08-16) to reproduce the `service` string most sibling
     * [AbstractOutboxBacklogGauge] subclasses already hardcode, so the outbox-dispatch and
     * outbox-backlog panels of the same dashboard share one label vocabulary. Three dispatchers
     * whose derivation would silently disagree with their own gauge override it explicitly:
     * `CardOutboxDispatcher` (`card-issuance`, not `card`), `TppOutboxDispatcher`
     * (`tpp-registry`, not `tpp`), `DomesticPaymentOutboxDispatcher` (`domestic`, not
     * `domestic-payment`). A new dispatcher whose class name does not already match its own
     * gauge's `service` must override this the same way.
     *
     * **Not CI-enforced (#5128 finding 5/8).** Nothing compares this derivation against the
     * fleet's actual gauge labels, so agreement rests on the 3 overrides above staying complete
     * and correct by hand — a prior version of this comment claimed a specific verified count of
     * sibling gauges, which itself went stale (grep patterns for a hand-written class hierarchy
     * are fragile across formatting styles). Verify directly instead of trusting a number here:
     * `grep -rl ': AbstractOutboxBacklogGauge' --include='*.kt' .` for the gauge side,
     * [deriveServiceName] for the dispatcher side.
     *
     * **`this::class.java.simpleName` is NOT the class you wrote (issue #5143).** This getter is
     * inherited from the abstract base, so `this` at the call site is Quarkus Arc's generated
     * bean subclass, not the developer's `LedgerOutboxDispatcher` — `simpleName` came back
     * `"LedgerOutboxDispatcher_Subclass"` in production, confirmed live: the first real dispatch
     * on `ledger-service` after this mechanism deployed recorded
     * `openbank_outbox_dispatched_total{service="ledger-outbox-dispatcher_-subclass",...}` while
     * the sibling `openbank_outbox_backlog` gauge on the SAME dispatcher, same pod, same moment,
     * correctly read `service="ledger"` — that gauge's `service` is `abstract`, forcing an
     * explicit value per subclass, which is exactly why it was never exposed to this bug. Not an
     * edge case: `@ApplicationScoped` is the standard scope every dispatcher in the fleet uses,
     * so every one relying on the default derivation was affected, and the 3 overrides above were
     * immune only by coincidence (they exist for a naming disagreement, not this). See
     * [deriveServiceName] for the fix.
     */
    // Lazy, not a plain val: it must still read `this::class.java.simpleName` at first access
    // (Arc's proxy is only fully constructed by then), but every subsequent outbox row on every
    // scheduled tick reuses the cached result instead of recompiling deriveServiceName's Regex
    // and re-walking the class name (#5128 finding 6) — the value cannot change for the life of
    // the bean, so recomputing it per-row was pure waste on a fleet-wide hot path.
    protected open val service: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        deriveServiceName(this::class.java.simpleName)
    }

    /**
     * Metrics facade, **constructor**-injected — matches [AbstractOutboxBacklogGauge]'s existing
     * pattern instead of the field-injection this class originally shipped with (#5128 finding 2).
     * Field injection (`@Inject protected var metrics: DomainMetrics? = null`) on an *abstract*
     * class extended by ~34 `@ApplicationScoped` subclasses across separate Gradle modules had no
     * precedent elsewhere in the fleet and no test proving CDI actually resolves it across that
     * module boundary — a silent-null risk if Arc's field discovery on an inherited superclass
     * field ever behaved unexpectedly. Constructor injection instead fails FAST: a concrete
     * subclass that doesn't pass `metrics` through to `super(metrics)` is a compile error, not a
     * runtime maybe. See `PartyOutboxDispatcherCdiIT` (openbank-party-service) and
     * `DocumentOutboxDispatcherCdiIT` (openbank-document-service) for the real-CDI proof — driven
     * through a booted `@QuarkusTest` container, in two different Gradle modules — that this
     * cross-module field injection actually resolved even before this change, and that
     * constructor injection resolves it too.
     */
    protected lateinit var metrics: DomainMetrics

    constructor(metrics: DomainMetrics) {
        this.metrics = metrics
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime, and
    // `metrics` is never read on this path (mirrors AbstractOutboxBacklogGauge's identical
    // second constructor).
    protected constructor()

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
        for (outcome in result.outcomes) {
            when (outcome) {
                is OutboxDispatchOutcome.Dispatched -> metrics.outboxDispatched(service, outcome.entry.eventType)
                is OutboxDispatchOutcome.Failed -> if (outcome.terminal) metrics.outboxDead(service)
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

        /**
         * `FooBarOutboxDispatcher` -> `foo-bar`. See [service] for why this exists.
         *
         * `substringBefore('_')` strips a Quarkus Arc-generated bean subclass suffix
         * (`_Subclass`, `_ClientProxy`, ...) BEFORE the kebab-case step — required because
         * [simpleClassName] is read via `this::class.java.simpleName` from a method inherited
         * from this abstract class, so at runtime `this` is Arc's generated instance, not the
         * developer's class (issue #5143). Cutting at the first underscore rather than
         * enumerating Arc's exact suffix vocabulary is deliberate: no dispatcher class name in
         * the fleet contains one (`git grep '^class.*OutboxDispatcher'` — none do, and this
         * repo's naming convention is PascalCase throughout), so it is a safe general strip that
         * does not need updating if a future Quarkus version generates a different marker.
         */
        internal fun deriveServiceName(simpleClassName: String): String = simpleClassName
            .substringBefore('_')
            .removeSuffix("OutboxDispatcher")
            .replace(Regex("(?<!^)(?=[A-Z])"), "-")
            .lowercase()
    }
}
