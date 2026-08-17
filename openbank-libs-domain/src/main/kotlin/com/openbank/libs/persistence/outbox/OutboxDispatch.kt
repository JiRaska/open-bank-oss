// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

/**
 * Shared outbox dispatch loop. Service-level dispatchers should:
 *   1. annotate their own `@Scheduled(every = "5s", ...)` method
 *   2. delegate to [dispatchOnce], injecting their service's [OutboxRepository] and a
 *      `publishWithResilience` lambda that carries the service-specific @CircuitBreaker /
 *      @Retry / @Bulkhead annotations.
 *
 * Keeping the @Scheduled and @CircuitBreaker annotations service-side preserves CDI proxying
 * (interceptors only fire on direct CDI injection, not on a libs-side base class).
 *
 * Rows are obtained via [OutboxRepository.claimProcessable], not `listProcessable` directly —
 * on a repository with a real atomic claim implementation this prevents two concurrently
 * running dispatcher instances (e.g. both pods live during an Argo Rollouts canary window)
 * from selecting and publishing the same rows (#1201). On a repository still using the
 * `claimProcessable` default, this is behaviourally identical to the old unclaimed peek.
 */
/**
 * Outcome of one claimed row's publish attempt (#5049): distinguishes a delivered event from a
 * real-but-retryable failure from a terminal DEAD row, so a runtime-layer caller (see
 * `AbstractOutboxDispatcher.dispatchScheduledBatch`) can attribute
 * `DomainMetrics.outboxDispatched`/`.outboxDead` correctly without duplicating
 * [OutboxFailurePolicy]'s terminal-vs-retry decision. [Failed.terminal] mirrors exactly what the
 * row's own `markFailed` independently computes — both read the SAME
 * [OutboxFailurePolicy.statusAfterFailure] over the entry's pre-failure `attemptCount + 1`, so
 * this can never disagree with the status a repository implementation actually persists (see
 * every `<Service>OutboxRepositoryImpl.applyFailure`, which does the identical computation).
 */
sealed class OutboxDispatchOutcome {
    abstract val entry: OutboxEntry

    /** The row published successfully and was marked SENT. */
    data class Dispatched(override val entry: OutboxEntry) : OutboxDispatchOutcome()

    /** The row failed to publish. [terminal] is true when this failure parked it DEAD (N5). */
    data class Failed(override val entry: OutboxEntry, val terminal: Boolean) : OutboxDispatchOutcome()
}

/**
 * Result of one [OutboxDispatch.dispatchOnce] batch: the per-row outcome for every entry this
 * call actually attempted to publish. A row left untouched by a batch abandoned mid-way (see
 * [OutboxDispatch.isTransportUnavailable]) is simply absent — not a synthetic `Failed`, since no
 * attempt was made against it.
 */
data class OutboxDispatchResult(val outcomes: List<OutboxDispatchOutcome> = emptyList()) {
    /** Count of rows this batch delivered successfully. */
    val dispatchedCount: Int get() = outcomes.count { it is OutboxDispatchOutcome.Dispatched }

    /** Count of rows this batch parked as terminal DEAD (ADR-0050 N5). */
    val deadCount: Int get() = outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }
}

object OutboxDispatch {
    // JDK System.Logger, not org.jboss.logging.Logger — this module must stay framework-free
    // (ADR-0002/ADR-0122, #3670). Same category, same destination: under Quarkus the JDK
    // logger bridges into the JBoss LogManager via JUL.
    private val log: System.Logger = System.getLogger(OutboxDispatch::class.java.name)

    const val DEFAULT_BATCH_SIZE = 25

    /** Guards against a self-referencing or pathological `cause` chain in [isTransportUnavailable]. */
    private const val MAX_CAUSE_DEPTH = 10

    /**
     * Fault-tolerance exceptions that are thrown **instead of** invoking the publish, matched by
     * fully-qualified class name.
     *
     * Name matching, not `is CircuitBreakerOpenException`: this module has zero framework imports
     * (ADR-0002/ADR-0122, `check-domain-purity.py`), and a hard reference would also risk a
     * `NoClassDefFoundError` in a service that has no fault-tolerance extension on its classpath.
     *
     * Both are *system* signals, not row signals. `CircuitBreakerOpenException` means the breaker
     * is open — the interceptor short-circuits and the publisher is never called;
     * `BulkheadException` means the concurrency permit was refused, likewise before invocation.
     * Neither says anything about the row, so neither may consume the row's attempt budget.
     * `TimeoutException` is deliberately **not** here: a timeout can fire after the record already
     * reached the broker, so it must keep counting as a real attempt.
     */
    val TRANSPORT_UNAVAILABLE_EXCEPTIONS: Set<String> = setOf(
        "org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException",
        "org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException",
    )

    /** True when [error] (or any cause of it) is one of [TRANSPORT_UNAVAILABLE_EXCEPTIONS]. */
    fun isTransportUnavailable(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current.javaClass.name in TRANSPORT_UNAVAILABLE_EXCEPTIONS) return true
            val next = current.cause
            if (next === current) return false
            current = next
            depth++
        }
        return false
    }

    /**
     * Publish one claimed batch.
     *
     * **A fast-fail from an open breaker is not a delivery attempt (#4005).** `markFailed`
     * increments `attempt_count` and applies [OutboxFailurePolicy], so counting a
     * `CircuitBreakerOpenException` against a row lets a broker outage — not a poison payload —
     * drive rows to terminal `DEAD`. Measured on card-issuance: the breaker opened, every tick
     * re-claimed the same 24 rows, each fast-failed in microseconds, and 10 ticks (~50 s) later
     * all 24 were `DEAD` with `last_error = "... circuit breaker is open"` — never once actually
     * offered to Kafka. `DEAD` is terminal and excluded from `listProcessable`, so nothing ever
     * retried them again: the breaker healed on the next pod restart, the rows did not, and the
     * breaker's own half-open probe had no work left to probe with.
     *
     * So on that signal the batch is **abandoned untouched**: no `markFailed`, no attempt burned.
     * The rows a claiming repository already flipped to `DISPATCHING` are picked up again by its
     * stale-claim reclaim (the same path that recovers a pod which died mid-batch); a repository
     * on the unclaimed-peek default sees them again on the very next tick. Either way the work
     * stays processable, which is what lets the breaker close on its own.
     *
     * A row still reaches `DEAD` after [OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS] *real* failed
     * publishes — that is the poison-row case `DEAD` exists for (ADR-0050 N5) and is unchanged.
     */
    suspend fun dispatchOnce(
        repository: OutboxRepository,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        publish: suspend (entry: OutboxEntry) -> Unit,
    ): OutboxDispatchResult {
        val claimed = runCatching { repository.claimProcessable(batchSize) }
            .onFailure { ex -> log.log(System.Logger.Level.WARNING, "outbox.claimProcessable failed", ex) }
            .getOrNull() ?: return OutboxDispatchResult()

        val outcomes = mutableListOf<OutboxDispatchOutcome>()
        for ((index, entry) in claimed.withIndex()) {
            try {
                publish(entry)
                repository.markSent(entry.eventId)
                outcomes += OutboxDispatchOutcome.Dispatched(entry)
            } catch (ex: Exception) {
                if (isTransportUnavailable(ex)) {
                    log.log(
                        System.Logger.Level.WARNING,
                        "outbox.dispatch abandoned: transport unavailable (${ex.javaClass.name}), " +
                            "${claimed.size - index} row(s) left for the next tick — no attempt consumed",
                        ex,
                    )
                    return OutboxDispatchResult(outcomes)
                }
                repository.markFailed(entry.eventId, ex.message ?: ex.javaClass.simpleName)
                // Same computation `markFailed` applies internally (see the class doc on
                // OutboxDispatchOutcome) — attemptCount BEFORE this failure was recorded, so the
                // post-failure count is entry.attemptCount + 1.
                val terminal = OutboxFailurePolicy.statusAfterFailure(entry.attemptCount + 1) == OutboxStatus.DEAD
                outcomes += OutboxDispatchOutcome.Failed(entry, terminal)
            }
        }
        return OutboxDispatchResult(outcomes)
    }
}
