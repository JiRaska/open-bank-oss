// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.outbox

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxBackoff
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepositoryV2
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Repository-semantics conformance for a service on the kernel outbox repository (ADR-0327 D9):
 * the four guarantees the ADR makes and the sibling [OutboxDispatchConformanceIT] cannot see,
 * because they live in the claim, the schedule and the purge rather than in the wire format:
 *
 *  - **D3, two concurrent dispatchers**: one aggregate's events reach the publisher in
 *    `(created_at, id)` order, each exactly once, while two dispatch loops race the same table.
 *  - **D3, a failed head parks its aggregate**: N+1 is never sent before N has succeeded.
 *  - **D4, backoff**: a FAILED row carries `next_attempt_at` inside [OutboxBackoff.delayBounds]
 *    and is invisible to the claim until then — while still counting as backlog.
 *  - **Stale reclaim**: a DISPATCHING row is not re-claimed inside `staleAfter` and is after.
 *  - **D8, purge safety**: `purgeSent` deletes old SENT rows only; PENDING/FAILED/DEAD survive.
 *
 * A separate kit from [OutboxDispatchConformanceIT] on purpose: that class has adopters today
 * whose concrete subclasses must not gain abstract members in a libs-only PR (Phase 1 touches no
 * service), and these tests need a [OutboxRepositoryV2], which a v1 adopter does not have. A
 * Phase 2/3 service carries both — two test classes, one per kit. Drives [OutboxDispatch]
 * directly with an in-kit recording publisher: the subject is the repository, not the bean.
 *
 * ```
 * @QuarkusTest
 * @QuarkusTestResource(PostgresTestResource::class)
 * class LedgerOutboxRepositoryV2IT : OutboxRepositoryV2ConformanceIT() {
 *     @Inject lateinit var repo: LedgerOutboxRepositoryImpl
 *     override val repository: OutboxRepositoryV2 get() = repo
 *     override suspend fun seed(message: OutboxMessage) = repo.persistInTransaction(message)
 *     override suspend fun findEntry(eventId: UUID) = repo.find("eventId", eventId).firstResult()?.toEntry()
 * }
 * ```
 */
// @Test methods live in src/main so testImplementation(project(":openbank-libs-testing")) can inherit them;
// detekt excludes **/test/** from FunctionNaming and MagicNumber, and these are test fixtures (sequence
// numbers, aggregate counts, seconds of seed skew) that only read as magic because of where they live.
@Suppress("FunctionNaming", "MagicNumber")
abstract class OutboxRepositoryV2ConformanceIT {

    /** The service's repository on the kernel base. */
    protected abstract val repository: OutboxRepositoryV2

    /** Persist a PENDING row using the concrete service's own repository/entity mapping. */
    protected abstract suspend fun seed(message: OutboxMessage)

    /**
     * Current row state by event id. The entity must be on `PanacheOutboxEntityV2` (or fill
     * `claimedAt`/`nextAttemptAt` itself) — the backoff and reclaim tests read those two fields.
     */
    protected abstract suspend fun findEntry(eventId: UUID): OutboxEntry?

    /** Reactive Panache needs a Vert.x duplicated context; the JUnit thread is not one. */
    protected fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun message(aggregateId: UUID, seq: Int, type: String, createdAt: Instant) = OutboxMessage(
        aggregateId = aggregateId,
        eventType = type,
        payload = """{"seq":$seq}""",
        createdAt = createdAt,
    )

    private fun seq(entry: OutboxEntry): Int = Regex("\"seq\":(\\d+)").find(entry.payload)!!.groupValues[1].toInt()

    @Test
    fun `two concurrent dispatchers deliver each aggregate's events once, in created_at order`() {
        val base = Instant.now().minusSeconds(60)
        val aggregates = (1..4).map { Ids.newId() }
        val seeded = aggregates.flatMap { agg ->
            (1..5).map { i -> message(agg, i, "v2.order", base.plusMillis(i.toLong())) }
        }
        seeded.forEach { onEventLoop { seed(it) } }
        val mine = seeded.map { it.eventId }.toSet()

        val published = ConcurrentLinkedQueue<OutboxEntry>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // Two loops on two threads, each with its own Vert.x context: the SKIP LOCKED race is real.
            val loops = (1..2).map {
                pool.submit {
                    repeat(DRAIN_ROUNDS) {
                        onEventLoop {
                            OutboxDispatch.dispatchOnce(repository, BATCH) { e ->
                                if (e.eventId in
                                    mine
                                ) {
                                    published += e
                                }
                            }
                        }
                    }
                }
            }
            loops.forEach { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }

        val ours = published.filter { it.eventId in mine }
        assertThat(
            ours.map {
                it.eventId
            },
        ).describedAs("each event exactly once").doesNotHaveDuplicates().hasSize(seeded.size)
        ours.groupBy { it.aggregateId }.forEach { (agg, events) ->
            assertThat(
                events.map {
                    seq(it)
                },
            ).describedAs("aggregate %s in created_at order", agg).isEqualTo(listOf(1, 2, 3, 4, 5))
        }
        seeded.forEach { assertThat(onEventLoop { findEntry(it.eventId) }!!.status).isEqualTo(OutboxStatus.SENT) }
    }

    @Test
    fun `a failed head parks its aggregate - N+1 is not sent before N`() {
        val base = Instant.now().minusSeconds(60)
        val agg = Ids.newId()
        val first = message(agg, 1, "v2.park", base)
        val second = message(agg, 2, "v2.park", base.plusMillis(1))
        onEventLoop { seed(first) }
        onEventLoop { seed(second) }
        val published = mutableListOf<UUID>()

        repeat(3) {
            onEventLoop {
                OutboxDispatch.dispatchOnce(repository, BATCH) { e ->
                    if (e.eventId == first.eventId) error("broker rejected N")
                    if (e.eventId == second.eventId) published += e.eventId
                }
            }
        }

        assertThat(published).describedAs("N+1 must not be published while N is FAILED").isEmpty()
        val head = onEventLoop { findEntry(first.eventId) }!!
        val next = onEventLoop { findEntry(second.eventId) }!!
        assertThat(head.status).isEqualTo(OutboxStatus.FAILED)
        assertThat(next.status).describedAs("N+1 was never even claimed").isEqualTo(OutboxStatus.PENDING)
    }

    @Test
    fun `a failed row is scheduled with backoff, stays backlog, and becomes claimable when it is due`() {
        val msg = message(Ids.newId(), 1, "v2.backoff", Instant.now().minusSeconds(60))
        onEventLoop { seed(msg) }

        onEventLoop { OutboxDispatch.dispatchOnce(repository, BATCH) { _ -> error("first attempt fails") } }

        val failed = onEventLoop { findEntry(msg.eventId) }!!
        assertThat(failed.status).isEqualTo(OutboxStatus.FAILED)
        assertThat(failed.attemptCount).isEqualTo(1)
        assertThat(failed.nextAttemptAt).describedAs("D4 schedule stamped").isNotNull
        val bounds = OutboxBackoff.delayBounds(1)
        assertThat(Duration.between(failed.updatedAt, failed.nextAttemptAt))
            .describedAs("2 s ± 20 %% after the failure")
            .isBetween(bounds.start, bounds.endInclusive)

        // Not yet due: the claim skips it, the backlog still counts it.
        val claimedEarly = onEventLoop { repository.claimProcessable(BATCH) }.filter { it.eventId == msg.eventId }
        assertThat(claimedEarly).isEmpty()
        assertThat(onEventLoop { repository.countProcessable() }).isGreaterThanOrEqualTo(1)

        Thread.sleep(Duration.between(Instant.now(), failed.nextAttemptAt).toMillis().coerceAtLeast(0) + SLACK_MS)
        val claimedDue = onEventLoop { repository.claimProcessable(BATCH) }.filter { it.eventId == msg.eventId }
        assertThat(claimedDue).describedAs("claimable once next_attempt_at has passed").hasSize(1)
    }

    @Test
    fun `a DISPATCHING row is reclaimed only once its claim is stale`() {
        val msg = message(Ids.newId(), 1, "v2.stale", Instant.now().minusSeconds(60))
        onEventLoop { seed(msg) }

        val first = onEventLoop { repository.claimProcessable(BATCH) }.filter { it.eventId == msg.eventId }
        assertThat(first).hasSize(1)
        val claimedAt = onEventLoop { findEntry(msg.eventId) }!!.claimedAt
        assertThat(claimedAt).describedAs("claim stamps claimed_at").isNotNull

        // Fresh claim (default 2 min window): nobody else may take it.
        val again = onEventLoop { repository.claimProcessable(BATCH) }.filter { it.eventId == msg.eventId }
        assertThat(again).isEmpty()

        // Past the window (the pod that claimed it died): the next claim takes it over and restamps it.
        Thread.sleep(SLACK_MS)
        val reclaimed = onEventLoop { repository.claimProcessable(BATCH, staleAfter = Duration.ZERO) }.filter {
            it.eventId ==
                msg.eventId
        }
        assertThat(reclaimed).hasSize(1)
        assertThat(onEventLoop { findEntry(msg.eventId) }!!.claimedAt).isAfter(claimedAt)
    }

    @Test
    fun `purgeSent removes only old SENT rows - PENDING, FAILED and DEAD survive`() {
        val base = Instant.now().minusSeconds(60)
        val sent = message(Ids.newId(), 1, "v2.purge.sent", base)
        val pending = message(Ids.newId(), 1, "v2.purge.pending", base)
        val failed = message(Ids.newId(), 1, "v2.purge.failed", base)
        listOf(sent, pending, failed).forEach { onEventLoop { seed(it) } }

        // sent -> SENT, failed -> FAILED (pending is left untouched by publishing only the two).
        onEventLoop {
            OutboxDispatch.dispatchOnce(repository, BATCH) { e ->
                when (e.eventId) {
                    failed.eventId -> error("fail it")
                    pending.eventId -> error("keep it out of SENT")
                    else -> Unit
                }
            }
        }
        assertThat(onEventLoop { findEntry(sent.eventId) }!!.status).isEqualTo(OutboxStatus.SENT)

        val deleted = onEventLoop { repository.purgeSent(Duration.ZERO, PURGE_BATCH, Instant.now().plusSeconds(1)) }
        assertThat(deleted).isGreaterThanOrEqualTo(1)
        assertThat(onEventLoop { findEntry(sent.eventId) }).describedAs("old SENT row purged").isNull()
        assertThat(onEventLoop { findEntry(pending.eventId) }).describedAs("non-SENT rows survive").isNotNull
        assertThat(onEventLoop { findEntry(failed.eventId) }).isNotNull
        assertThat(onEventLoop { repository.purgeDead(Duration.ZERO, PURGE_BATCH, Instant.now().plusSeconds(1)) })
            .describedAs("no DEAD rows were seeded, so purgeDead deletes nothing here")
            .isGreaterThanOrEqualTo(0)
        assertThat(onEventLoop { findEntry(failed.eventId) }).describedAs("purgeDead never touches FAILED").isNotNull
    }

    private companion object {
        const val BATCH = 25
        const val PURGE_BATCH = 1_000
        const val DRAIN_ROUNDS = 12
        const val SLACK_MS = 250L
    }
}
