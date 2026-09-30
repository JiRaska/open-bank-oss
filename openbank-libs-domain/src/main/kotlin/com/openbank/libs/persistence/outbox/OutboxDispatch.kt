// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

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
 * [OutboxFailurePolicy]'s terminal-vs-retry decision. [Failed.terminal] is read directly from the
 * [OutboxStatus] [OutboxRepository.markFailed] returns — the status the repository actually
 * persisted — rather than independently recomputed by this class (#5128 finding 3: recomputing it
 * here could only ever agree with a repository's own write by convention, never by the type
 * system).
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
data class OutboxDispatchResult(
    val outcomes: List<OutboxDispatchOutcome> = emptyList(),
    /** Rows the claim returned — what [OutboxDispatch.drain] compares against `batchSize`. */
    val claimed: Int = outcomes.size,
    /** True when the batch was abandoned on a transport-unavailable signal (see [OutboxDispatch.dispatchOnce]). */
    val abandoned: Boolean = false,
)

object OutboxDispatch {
    // JDK System.Logger, not org.jboss.logging.Logger — this module must stay framework-free
    // (ADR-0002/ADR-0122, #3670). Same category, same destination: under Quarkus the JDK
    // logger bridges into the JBoss LogManager via JUL.
    private val log: System.Logger = System.getLogger(OutboxDispatch::class.java.name)

    const val DEFAULT_BATCH_SIZE = 25

    /**
     * Sends in flight at once for one [OutboxRepositoryV2] batch (ADR-0327 D6). Legal only
     * because such a batch holds one row per aggregate — see [OutboxRepositoryV2].
     */
    const val SEND_CONCURRENCY = 16

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
        claimObserver: ((Duration) -> Unit)? = null,
        publish: suspend (entry: OutboxEntry) -> Unit,
    ): OutboxDispatchResult {
        val claimStart = System.nanoTime()
        val claimed = runCatching { repository.claimProcessable(batchSize) }
            .onFailure { ex ->
                if (ex is CancellationException) throw ex
                log.log(System.Logger.Level.WARNING, "outbox.claimProcessable failed", ex)
            }
            .getOrNull() ?: return OutboxDispatchResult()
        claimObserver?.invoke(Duration.ofNanos(System.nanoTime() - claimStart))

        return if (repository is OutboxRepositoryV2) {
            dispatchConcurrently(repository, claimed, publish)
        } else {
            dispatchSequentially(repository, claimed, publish)
        }
    }

    /**
     * Drain loop (ADR-0327 D5): call [dispatchOnce] until a batch comes back **short** (fewer
     * rows than [batchSize] — the table is empty or every remaining aggregate is parked), the
     * wall-clock [budget] is spent, or a batch was abandoned on a transport-unavailable signal
     * (looping on an open breaker would just burn ticks). A burst of 1 000 rows therefore drains
     * in one tick instead of forty; a service on the v1 path is unaffected because its dispatcher
     * only calls this with a [OutboxRepositoryV2] repository (see `AbstractOutboxDispatcher`).
     * The caller's `@Scheduled(concurrentExecution = SKIP)` keeps ticks from overlapping.
     */
    suspend fun drain(
        repository: OutboxRepository,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        budget: Duration,
        claimObserver: ((Duration) -> Unit)? = null,
        publish: suspend (entry: OutboxEntry) -> Unit,
    ): OutboxDispatchResult {
        val deadline = System.nanoTime() + budget.toNanos()
        val outcomes = mutableListOf<OutboxDispatchOutcome>()
        var claimed = 0
        var abandoned = false
        do {
            val result = dispatchOnce(repository, batchSize, claimObserver, publish)
            outcomes += result.outcomes
            claimed += result.claimed
            abandoned = result.abandoned
            val short = result.claimed < batchSize
        } while (!short && !abandoned && System.nanoTime() < deadline)
        return OutboxDispatchResult(outcomes, claimed, abandoned)
    }

    /** The v1 loop: one row at a time, `markSent` per row. Behaviour unchanged except for D4's cancellation rethrow. */
    private suspend fun dispatchSequentially(
        repository: OutboxRepository,
        claimed: List<OutboxEntry>,
        publish: suspend (entry: OutboxEntry) -> Unit,
    ): OutboxDispatchResult {
        val outcomes = mutableListOf<OutboxDispatchOutcome>()
        for ((index, entry) in claimed.withIndex()) {
            try {
                publish(entry)
                repository.markSent(entry.eventId)
                outcomes += OutboxDispatchOutcome.Dispatched(entry)
            } catch (ex: CancellationException) {
                // D4: a cancelled scope must stop publishing, not record the cancellation as a
                // row failure and carry on to the next row. `CancellationException` IS an
                // `Exception`, so the clause below would otherwise swallow it (ADR-0327 finding 3).
                throw ex
            } catch (ex: Exception) {
                if (isTransportUnavailable(ex)) {
                    log.log(
                        System.Logger.Level.WARNING,
                        "outbox.dispatch abandoned: transport unavailable (${ex.javaClass.name}), " +
                            "${claimed.size - index} row(s) left for the next tick — no attempt consumed",
                        ex,
                    )
                    return OutboxDispatchResult(outcomes, claimed.size, abandoned = true)
                }
                // Read back what markFailed actually persisted rather than independently
                // recomputing OutboxFailurePolicy.statusAfterFailure over entry.attemptCount + 1
                // (#5128 finding 3) — the two could only ever agree by convention (every
                // <Service>OutboxRepositoryImpl.markFailed happening to apply the identical
                // policy with the same maxAttempts), never by the type system, and a future repo
                // impl with different backoff/maxAttempts would silently desync this metric from
                // the DB's real row status.
                val persistedStatus = repository.markFailed(entry.eventId, ex.message ?: ex.javaClass.simpleName)
                outcomes += OutboxDispatchOutcome.Failed(entry, terminal = persistedStatus == OutboxStatus.DEAD)
            }
        }
        return OutboxDispatchResult(outcomes, claimed.size)
    }

    /**
     * The v2 loop (ADR-0327 D6): every row of a [OutboxRepositoryV2] batch belongs to a different
     * aggregate (the repository's claim contract), so the sends are independent and run under
     * [SEND_CONCURRENCY] permits; the successes are then acknowledged with ONE
     * [OutboxRepositoryV2.markSentBatch] and each real failure with its own `markFailed` (which
     * applies D4's backoff). Transactions per batch: 25 → 2.
     *
     * A transport-unavailable signal (#4005) still abandons the batch: sends not yet started are
     * skipped, rows that fast-failed on it get no `markFailed`, and everything left DISPATCHING is
     * reclaimed by the stale-claim window. Rows that had already succeeded are still marked SENT —
     * they reached the broker, and leaving them DISPATCHING would re-send them.
     */
    private suspend fun dispatchConcurrently(
        repository: OutboxRepositoryV2,
        claimed: List<OutboxEntry>,
        publish: suspend (entry: OutboxEntry) -> Unit,
    ): OutboxDispatchResult {
        val permits = Semaphore(SEND_CONCURRENCY)
        val abandoned = AtomicBoolean(false)
        val attempts: List<SendAttempt> = coroutineScope {
            claimed.map { entry ->
                async {
                    permits.withPermit {
                        if (abandoned.get()) return@withPermit SendAttempt.Skipped(entry)
                        try {
                            publish(entry)
                            SendAttempt.Sent(entry)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            if (isTransportUnavailable(ex)) {
                                abandoned.set(true)
                                SendAttempt.TransportUnavailable(entry, ex)
                            } else {
                                SendAttempt.Failed(entry, ex)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val outcomes = mutableListOf<OutboxDispatchOutcome>()
        val sent = attempts.filterIsInstance<SendAttempt.Sent>()
        if (sent.isNotEmpty()) {
            repository.markSentBatch(sent.map { it.entry.eventId })
            sent.forEach { outcomes += OutboxDispatchOutcome.Dispatched(it.entry) }
        }
        for (failure in attempts.filterIsInstance<SendAttempt.Failed>()) {
            val ex = failure.error
            val persistedStatus = repository.markFailed(failure.entry.eventId, ex.message ?: ex.javaClass.simpleName)
            outcomes += OutboxDispatchOutcome.Failed(failure.entry, terminal = persistedStatus == OutboxStatus.DEAD)
        }
        val unavailable = attempts.filterIsInstance<SendAttempt.TransportUnavailable>()
        if (unavailable.isNotEmpty()) {
            val left = unavailable.size + attempts.count { it is SendAttempt.Skipped }
            log.log(
                System.Logger.Level.WARNING,
                "outbox.dispatch abandoned: transport unavailable (${unavailable.first().error.javaClass.name}), " +
                    "$left row(s) left for the stale-claim reclaim — no attempt consumed",
                unavailable.first().error,
            )
        }
        return OutboxDispatchResult(outcomes, claimed.size, abandoned = unavailable.isNotEmpty())
    }

    private sealed class SendAttempt {
        abstract val entry: OutboxEntry

        data class Sent(override val entry: OutboxEntry) : SendAttempt()
        data class Failed(override val entry: OutboxEntry, val error: Exception) : SendAttempt()
        data class TransportUnavailable(override val entry: OutboxEntry, val error: Exception) : SendAttempt()
        data class Skipped(override val entry: OutboxEntry) : SendAttempt()
    }
}
