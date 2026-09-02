// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared outbox **dead-letter** gauge (#4005), sibling of [AbstractOutboxBacklogGauge].
 *
 * Publishes the per-service count of rows parked in terminal [OutboxStatus.DEAD] as the
 * `openbank.outbox.dead_lettered` gauge tagged `service=<name>`.
 *
 * ### Why this is not covered by the backlog gauge
 * `openbank.outbox.backlog` counts PENDING + FAILED and deliberately excludes DEAD, because DEAD
 * is not work waiting to be done. That is correct and it is also the blind spot: a service that
 * dead-lettered *every* event it ever produced publishes a backlog of `0`, which on a dashboard
 * and in an alert rule is byte-for-byte what a healthy service publishes. Dead-lettering is
 * terminal by design (ADR-0050 N5) — nothing retries a DEAD row — so it needs its own signal or
 * it has none.
 *
 * ### Why a gauge and not the `openbank.outbox.dead` counter
 * The counter answers "did this process dead-letter anything since it started". A pod restart
 * resets it while the rows stay in the table, and Micrometer does not create a counter until its
 * first increment — so a service whose dead-lettering happened before the current pod exports no
 * `openbank_outbox_dead_total` series at all and an alert on it matches nothing, forever, reading
 * exactly like "no problem". The gauge is read from the table, so it survives restarts and is
 * true about state rather than about this process's history.
 *
 * A concrete subclass binds the service's outbox repository and `DomainMetrics`:
 * ```
 * @Startup
 * @ApplicationScoped
 * class PartyOutboxDeadLetterGauge(
 *     private val outboxRepository: PartyOutboxRepository,
 *     metrics: DomainMetrics,
 * ) : AbstractOutboxDeadLetterGauge(metrics) {
 *     override val service: String = "party"
 *     override suspend fun currentDeadLettered(): Long = outboxRepository.countDead()
 *
 *     @PostConstruct
 *     fun register() {
 *         registerDeadLetterGauge()
 *         bindLiveness(metrics.registerWorkflowLiveness("party-outbox-dead-letter-gauge", INTERVAL))
 *     }
 *
 *     @Scheduled(every = "60s", delayed = "10s",
 *                concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
 *     suspend fun refresh() = refreshDeadLettered()
 * }
 * ```
 *
 * ### The two constraints inherited from [AbstractOutboxBacklogGauge]
 * 1. Micrometer samples a gauge supplier synchronously on the Prometheus scrape (worker) thread,
 *    but the count is a reactive query — so a scheduled `suspend` tick refreshes a cached
 *    [AtomicLong] on the right context and the supplier reads that cache lock-free.
 * 2. **CDI proxying (ADR-0013):** `@PostConstruct` and `@Scheduled` must sit on the concrete
 *    `@ApplicationScoped` bean's methods, never on this base — lifecycle and scheduler
 *    interception only fire for methods declared on the bean the proxy wraps. Note also that the
 *    refresh method must be a `suspend fun`: a plain `@Scheduled` method carries no Vert.x
 *    context, so `runBlocking { }` around the reactive count throws `HR000068` and the tick dies
 *    silently, leaving the gauge frozen at its last value (or at the boot zero, which is the
 *    reading that looks healthiest).
 *
 * ### ADR-0237 liveness — why this base does NOT take the outbox exemption
 * `check-scheduler-liveness.py` exempts outbox infrastructure "by role", on the grounds that
 * `openbank.outbox.backlog` is itself the freshness signal. That reasoning does not carry over
 * here: the alert on this gauge is `> 0`, so a tick that dies leaves it pinned at the boot **zero**
 * — the value that reads as perfectly healthy — and the alert then never fires, which is the exact
 * class of silence #4005 is about. So [registerDeadLetterGauge] also registers an ADR-0237
 * heartbeat and [refreshDeadLettered] records a success on every tick.
 *
 * The heartbeat is REGISTERED BY THE CONCRETE BEAN and handed here via [bindLiveness], not
 * registered in this base. That is not ceremony: `check-scheduler-liveness.py` classifies the file
 * that declares `@Scheduled`, so a heartbeat hidden in a shared superclass leaves every subclass
 * looking like an unmonitored scheduler to the gate — and the honest way to satisfy a text-matching
 * gate is to put the call where it says, not to mention its name in a comment.
 */
abstract class AbstractOutboxDeadLetterGauge {
    private lateinit var metrics: DomainMetrics
    private val cached = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null

    constructor(metrics: DomainMetrics) {
        this.metrics = metrics
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime
    protected constructor()

    /** The `service` tag value for the gauge (the short service name, e.g. `"card-issuance"`). */
    protected abstract val service: String

    /** Current count of terminal DEAD rows. Implemented by querying the service's repository. */
    protected abstract suspend fun currentDeadLettered(): Long

    /**
     * Register the lock-free gauge supplier. Call from the concrete bean's `@PostConstruct`.
     * The supplier reads the cache refreshed by [refreshDeadLettered].
     */
    protected fun registerDeadLetterGauge() {
        metrics.registerOutboxDeadLettered(service) { cached.get() }
    }

    /**
     * Hand this base the ADR-0237 heartbeat the concrete bean registered, so
     * [refreshDeadLettered] can mark each successful tick. Call from the same `@PostConstruct`.
     */
    protected fun bindLiveness(recorder: WorkflowLivenessRecorder) {
        liveness = recorder
    }

    /**
     * Refresh the cached DEAD count from the repository. Call from the concrete bean's
     * `@Scheduled` **`suspend`** method so the reactive query runs on the right context.
     */
    protected suspend fun refreshDeadLettered() {
        cached.set(currentDeadLettered())
        liveness?.recordSuccess()
    }
}
