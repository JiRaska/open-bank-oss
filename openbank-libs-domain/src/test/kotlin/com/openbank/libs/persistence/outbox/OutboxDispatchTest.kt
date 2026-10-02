// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

class OutboxDispatchTest {

    /** Minimal in-memory repository recording the dispatcher's bookkeeping calls.
     *
     * `markFailed` computes and RETURNS the resulting [OutboxStatus] the same way every real
     * `<Service>OutboxRepositoryImpl` does — [OutboxFailurePolicy.statusAfterFailure] over the
     * row's pre-failure `attemptCount + 1` — so these tests exercise [OutboxDispatch] reading that
     * return value back, not a value it recomputed itself (#5128 finding 3). */
    private class FakeRepo(private val rows: List<OutboxEntry>) : OutboxRepository {
        val sent = mutableListOf<UUID>()
        val failed = mutableListOf<Pair<UUID, String>>()
        override suspend fun listProcessable(limit: Int): List<OutboxEntry> = rows.take(limit)
        override suspend fun markSent(eventId: UUID, sentAt: Instant) {
            sent += eventId
        }
        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus {
            failed += eventId to error
            val entry = rows.first { it.eventId == eventId }
            return OutboxFailurePolicy.statusAfterFailure(entry.attemptCount + 1)
        }
    }

    private fun entry(type: String, attemptCount: Int = 0) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = type,
        payload = """{"t":"$type"}""",
        status = OutboxStatus.PENDING,
        attemptCount = attemptCount,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `publishes each processable row and marks it sent, passing the full entry`() {
        val rows = listOf(entry("a.created"), entry("b.created"))
        val repo = FakeRepo(rows)
        val published = mutableListOf<OutboxEntry>()

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e -> published += e }
        }

        // the dispatcher hands the whole entry (not just the payload) to the publisher
        assertThat(published).containsExactlyElementsOf(rows)
        assertThat(repo.sent).containsExactly(rows[0].eventId, rows[1].eventId)
        assertThat(repo.failed).isEmpty()
    }

    @Test
    fun `marks a row failed when its publish throws, and does not mark it sent`() {
        val row = entry("boom")
        val repo = FakeRepo(listOf(row))

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { throw IllegalStateException("kafka down") }
        }

        assertThat(repo.sent).isEmpty()
        assertThat(repo.failed).hasSize(1)
        assertThat(repo.failed.single().first).isEqualTo(row.eventId)
        assertThat(repo.failed.single().second).isEqualTo("kafka down")
    }

    private fun breakerOpen() =
        org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException("circuit breaker is open")

    @Test
    fun `an open breaker consumes no attempt and abandons the rest of the batch`() {
        val rows = listOf(entry("a.created"), entry("b.created"), entry("c.created"))
        val repo = FakeRepo(rows)
        val attempted = mutableListOf<UUID>()

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e ->
                attempted += e.eventId
                throw breakerOpen()
            }
        }

        // The first row is the only one offered; the batch stops there rather than burning an
        // attempt on every remaining row (#4005: 24 rows x 10 ticks -> all DEAD in ~50 s).
        assertThat(attempted).containsExactly(rows[0].eventId)
        assertThat(repo.failed).isEmpty()
        assertThat(repo.sent).isEmpty()
    }

    @Test
    fun `a breaker-open cause nested under another exception is still not an attempt`() {
        val row = entry("nested")
        val repo = FakeRepo(listOf(row))

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) {
                throw IllegalStateException("wrapped", breakerOpen())
            }
        }

        assertThat(repo.failed).isEmpty()
        assertThat(repo.sent).isEmpty()
    }

    @Test
    fun `a real publish failure still counts, so a poison row still reaches DEAD`() {
        val rows = listOf(entry("poison"), entry("healthy"))
        val repo = FakeRepo(rows)
        val attempted = mutableListOf<UUID>()

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e ->
                attempted += e.eventId
                if (e.eventType == "poison") error("serialization failed")
            }
        }

        // Unlike a breaker fast-fail, a genuine failure is recorded AND the batch continues.
        assertThat(attempted).containsExactly(rows[0].eventId, rows[1].eventId)
        assertThat(repo.failed.map { it.first }).containsExactly(rows[0].eventId)
        assertThat(repo.sent).containsExactly(rows[1].eventId)
    }

    @Test
    fun `a timeout is NOT treated as transport-unavailable`() {
        // A @Timeout can fire after the record already reached the broker, so it must keep
        // counting as a real attempt — otherwise the row is retried and the consumer sees a
        // duplicate. Deliberately excluded from TRANSPORT_UNAVAILABLE_EXCEPTIONS.
        assertThat(
            OutboxDispatch.isTransportUnavailable(
                java.util.concurrent.TimeoutException("publish timed out"),
            ),
        ).isFalse()
        assertThat(OutboxDispatch.isTransportUnavailable(breakerOpen())).isTrue()
        assertThat(OutboxDispatch.isTransportUnavailable(null)).isFalse()
    }

    // ── #5049: dispatchOnce's result must let a caller attribute dispatched-vs-dead correctly ──

    @Test
    fun `result reports one Dispatched outcome per successfully published row`() {
        val rows = listOf(entry("a.created"), entry("b.created"))
        val repo = FakeRepo(rows)

        val result = runBlocking { OutboxDispatch.dispatchOnce(repo) { } }

        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Dispatched }).isEqualTo(2)
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }).isZero()
        assertThat(result.outcomes).allMatch { it is OutboxDispatchOutcome.Dispatched }
        assertThat(result.outcomes.map { it.entry.eventId }).containsExactly(rows[0].eventId, rows[1].eventId)
    }

    @Test
    fun `a failure below the DEAD threshold is reported non-terminal`() {
        // attemptCount=0 -> post-failure count is 1, well under DEFAULT_MAX_ATTEMPTS (10).
        val row = entry("retryable", attemptCount = 0)
        val repo = FakeRepo(listOf(row))

        val result = runBlocking { OutboxDispatch.dispatchOnce(repo) { error("kafka down") } }

        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Dispatched }).isZero()
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }).isZero()
        val outcome = result.outcomes.single() as OutboxDispatchOutcome.Failed
        assertThat(outcome.terminal).isFalse()
    }

    @Test
    fun `a failure that exhausts the attempt budget is reported terminal (DEAD)`() {
        // attemptCount=9 -> post-failure count is 10 == DEFAULT_MAX_ATTEMPTS -> DEAD (ADR-0050 N5).
        val row = entry("poison", attemptCount = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1)
        val repo = FakeRepo(listOf(row))

        val result = runBlocking { OutboxDispatch.dispatchOnce(repo) { error("still failing") } }

        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Dispatched }).isZero()
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }).isEqualTo(1)
        val outcome = result.outcomes.single() as OutboxDispatchOutcome.Failed
        assertThat(outcome.terminal).isTrue()
    }

    @Test
    fun `a mixed batch reports dispatched, retryable-failed and dead counts independently`() {
        val sent = entry("sent")
        val retrying = entry("retrying", attemptCount = 2)
        val dying = entry("dying", attemptCount = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1)
        val repo = FakeRepo(listOf(sent, retrying, dying))

        val result = runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e ->
                if (e.eventType != "sent") error("publish failed for ${e.eventType}")
            }
        }

        // This is the falsifying assertion: a no-op/wrong wiring that always reports
        // dispatchedCount == claimed.size, or deadCount == failedCount, would fail here.
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Dispatched }).isEqualTo(1)
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }).isEqualTo(1)
        assertThat(result.outcomes.filterIsInstance<OutboxDispatchOutcome.Failed>()).hasSize(2)
        assertThat(
            result.outcomes.filterIsInstance<OutboxDispatchOutcome.Failed>().count { !it.terminal },
        ).isEqualTo(1)
    }

    @Test
    fun `terminal is read from markFailed's return value, not recomputed independently`() {
        // Falsifying test for #5128 finding 3: a repository whose markFailed applies a DIFFERENT
        // policy than OutboxFailurePolicy.statusAfterFailure(attemptCount + 1) -- e.g. a lower
        // maxAttempts -- must have that DISAGREEMENT show up in the outcome. Before the fix,
        // OutboxDispatch recomputed the terminal flag itself and this test would report
        // `terminal = false` (attemptCount + 1 = 1, nowhere near DEFAULT_MAX_ATTEMPTS) even though
        // the repository just persisted DEAD.
        val row = entry("low-tolerance", attemptCount = 0)
        val repo = object : OutboxRepository {
            override suspend fun listProcessable(limit: Int) = listOf(row)
            override suspend fun markSent(eventId: UUID, sentAt: Instant) = Unit
            override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
                // This repository's own policy parks a row DEAD after just ONE failure -- nothing
                // OutboxDispatch could derive from entry.attemptCount + 1 under the shared default
                // policy.
                OutboxStatus.DEAD
        }

        val result = runBlocking { OutboxDispatch.dispatchOnce(repo) { error("poison") } }

        val outcome = result.outcomes.single() as OutboxDispatchOutcome.Failed
        assertThat(outcome.terminal)
            .describedAs("must reflect the DEAD status markFailed actually returned")
            .isTrue()
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }).isEqualTo(1)
    }

    @Test
    fun `a batch abandoned by an open breaker returns only outcomes for rows actually attempted`() {
        val rows = listOf(entry("a"), entry("b"), entry("c"))
        val repo = FakeRepo(rows)

        val result = runBlocking {
            OutboxDispatch.dispatchOnce(repo) { throw breakerOpen() }
        }

        // The breaker aborts before the first row's publish is even attempted (#4005) — no
        // outcome at all, not a Failed(terminal = false).
        assertThat(result.outcomes).isEmpty()
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Dispatched }).isZero()
        assertThat(result.outcomes.count { it is OutboxDispatchOutcome.Failed && it.terminal }).isZero()
    }

    // ── claimProcessable failure path (previously PIT NO_COVERAGE: no test ever threw here) ──

    @Test
    fun `a claimProcessable failure returns an empty result with no outcomes and marks nothing`() {
        val repo = object : OutboxRepository {
            override suspend fun listProcessable(limit: Int) = emptyList<OutboxEntry>()
            override suspend fun claimProcessable(limit: Int, staleAfter: java.time.Duration): List<OutboxEntry> =
                error("db unavailable")
            override suspend fun markSent(eventId: UUID, sentAt: Instant) =
                error("must not be called: nothing was claimed")
            override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
                error("must not be called: nothing was claimed")
        }

        lateinit var result: OutboxDispatchResult
        val records = captureLog(OutboxDispatch::class.java.name) {
            result = runBlocking { OutboxDispatch.dispatchOnce(repo) { error("must not be called") } }
        }

        // Falsifying assertion: a mutant that swallows the exception and returns null, or that
        // rethrows instead of degrading gracefully, both fail this - the batch must come back
        // empty rather than crashing the scheduler tick.
        assertThat(result.outcomes).isEmpty()
        // The claim failure must actually be logged, not silently absorbed by a no-op onFailure.
        assertThat(records.map { it.message }).anyMatch { it.contains("outbox.claimProcessable failed") }
    }

    @Test
    fun `TRANSPORT_UNAVAILABLE_EXCEPTIONS names exactly the two fault-tolerance short-circuit types`() {
        assertThat(OutboxDispatch.TRANSPORT_UNAVAILABLE_EXCEPTIONS).containsExactlyInAnyOrder(
            "org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException",
            "org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException",
        )
    }

    // ── isTransportUnavailable's MAX_CAUSE_DEPTH guard (previously SURVIVED: boundary + increment) ──

    /** Builds a chain of [depth] wrapper exceptions with [root] at the bottom of the `cause` chain. */
    private fun wrapChain(depth: Int, root: Throwable): Throwable {
        var current = root
        repeat(depth) { current = RuntimeException("wrapper", current) }
        return current
    }

    @Test
    fun `a transport-unavailable cause more than MAX_CAUSE_DEPTH hops down is not found`() {
        // 10 wrapper hops puts the real cause exactly one hop past the guard's depth-10 cutoff:
        // the outermost wrapper is examined at depth 0, so the root lands at depth 10, and the
        // loop's `depth < MAX_CAUSE_DEPTH` (10) guard must already have stopped one iteration
        // earlier. A boundary off-by-one (`<=`) would run exactly one more iteration and find it,
        // wrongly answering true.
        val deeplyBuried = wrapChain(depth = 10, root = breakerOpen())

        assertThat(OutboxDispatch.isTransportUnavailable(deeplyBuried)).isFalse()
    }

    @Test
    fun `a transport-unavailable cause exactly at the depth cutoff is still found`() {
        // 9 wrapper hops: root is examined at depth 0, each wrapper bumps depth by one on the way
        // down, so the root is reached at depth 9 - inside the < 10 guard - and must be found.
        val justInBounds = wrapChain(depth = 9, root = breakerOpen())

        assertThat(OutboxDispatch.isTransportUnavailable(justInBounds)).isTrue()
    }

    // ── Abandoned-batch log content (previously SURVIVED: arithmetic on the log message + the
    // log call itself) — captured via the JUL logger the JDK System.Logger bridges to by default. ──

    private fun captureLog(loggerName: String, block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val julLogger = JulLogger.getLogger(loggerName)
        val previousLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = java.util.logging.Level.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = previousLevel
        }
        return records
    }

    @Test
    fun `an abandoned batch logs exactly how many rows were left for the next tick`() {
        val rows = listOf(entry("a"), entry("b"), entry("c"))
        val repo = FakeRepo(rows)

        val records = captureLog(OutboxDispatch::class.java.name) {
            runBlocking { OutboxDispatch.dispatchOnce(repo) { throw breakerOpen() } }
        }

        // 3 claimed, index 0 is where the breaker fires -> claimed.size - index = 3 - 0 = 3 left
        // (the aborted row itself is included: it was never actually offered to the publisher).
        // A flipped +/- on the remaining-count arithmetic, or a removed log call entirely, both
        // fail this.
        val message = records.joinToString("\n") { it.message }
        assertThat(message).contains("3 row(s) left")
    }

    @Test
    fun `an abandoned batch's remaining count reflects how many rows were already attempted`() {
        val rows = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        val repo = FakeRepo(rows)
        var calls = 0

        val records = captureLog(OutboxDispatch::class.java.name) {
            runBlocking {
                OutboxDispatch.dispatchOnce(repo) { e ->
                    calls++
                    if (e.eventType == "b") throw breakerOpen()
                }
            }
        }

        // a, b claimed/attempted (a sent, b trips the breaker at index 1) -> 4 - 1 = 3 left.
        val message = records.joinToString("\n") { it.message }
        assertThat(message).contains("3 row(s) left")
        assertThat(calls).isEqualTo(2)
    }

    // ── ADR-0327 v2 path ─────────────────────────────────────────────────────────

    /**
     * In-memory [OutboxRepositoryV2]: claims are one row per aggregate (the D3 head rule) so the
     * concurrent path's precondition holds; `markSentBatch` records batch boundaries so a test can
     * assert one acknowledgement per batch rather than one per row.
     */
    private class FakeRepoV2(initial: List<OutboxEntry>) : OutboxRepositoryV2 {
        val rows = initial.associateBy { it.eventId }.toMutableMap()
        val sentBatches = mutableListOf<List<UUID>>()
        val failed = mutableListOf<UUID>()
        val claims = AtomicInteger()

        override suspend fun listProcessable(limit: Int): List<OutboxEntry> = claimProcessable(limit)

        override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
            claims.incrementAndGet()
            val heads = rows.values
                .filter { it.status == OutboxStatus.PENDING || it.status == OutboxStatus.FAILED }
                .sortedBy { it.createdAt }
                .distinctBy { it.aggregateId }
                .take(limit)
            heads.forEach { rows[it.eventId] = it.copy(status = OutboxStatus.DISPATCHING) }
            return heads
        }

        override suspend fun markSent(eventId: UUID, sentAt: Instant) = markSentBatch(listOf(eventId), sentAt)

        override suspend fun markSentBatch(eventIds: Collection<UUID>, sentAt: Instant) {
            sentBatches += eventIds.toList()
            eventIds.forEach { id -> rows[id] = rows.getValue(id).copy(status = OutboxStatus.SENT, sentAt = sentAt) }
        }

        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus {
            failed += eventId
            val row = rows.getValue(eventId)
            val next = OutboxFailurePolicy.statusAfterFailure(row.attemptCount + 1)
            rows[eventId] = row.copy(status = next, attemptCount = row.attemptCount + 1, lastError = error)
            return next
        }

        override suspend fun oldestProcessableAge(now: Instant): Duration? = null
        override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int = 0
        override suspend fun purgeDead(olderThan: Duration, batch: Int, now: Instant): Int = 0
    }

    @Test
    fun `v2 path publishes a batch concurrently, bounded by the semaphore, with ONE markSentBatch`() {
        val rows = (1..40).map { entry("v2.$it") }
        val repo = FakeRepoV2(rows)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        val result = runBlocking(Dispatchers.Default) {
            OutboxDispatch.dispatchOnce(repo, batchSize = 40) { _ ->
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(20)
                inFlight.decrementAndGet()
            }
        }

        assertThat(result.outcomes).hasSize(40).allMatch { it is OutboxDispatchOutcome.Dispatched }
        assertThat(result.claimed).isEqualTo(40)
        assertThat(repo.sentBatches).describedAs("one acknowledgement per batch, not per row").hasSize(1)
        assertThat(repo.sentBatches.single()).containsExactlyInAnyOrderElementsOf(rows.map { it.eventId })
        assertThat(peak.get())
            .describedAs("sends overlap (the point of D6) but never beyond SEND_CONCURRENCY")
            .isGreaterThan(1)
            .isLessThanOrEqualTo(OutboxDispatch.SEND_CONCURRENCY)
    }

    @Test
    fun `v2 path marks each real failure individually and still acknowledges the successes`() {
        val rows = listOf(entry("ok.1"), entry("bad.1"), entry("ok.2"))
        val repo = FakeRepoV2(rows)

        val result = runBlocking {
            OutboxDispatch.dispatchOnce(repo, batchSize = 3) { e ->
                if (e.eventType.startsWith("bad")) error("broker said no")
            }
        }

        assertThat(repo.sentBatches.single()).containsExactlyInAnyOrder(rows[0].eventId, rows[2].eventId)
        assertThat(repo.failed).containsExactly(rows[1].eventId)
        assertThat(result.outcomes.filterIsInstance<OutboxDispatchOutcome.Failed>().single().terminal).isFalse()
    }

    @Test
    fun `v2 path abandons the batch on a transport-unavailable signal without consuming attempts`() {
        val rows = (1..5).map { entry("cb.$it") }
        val repo = FakeRepoV2(rows)

        val result = runBlocking {
            OutboxDispatch.dispatchOnce(repo, batchSize = 5) { _ -> throw breakerOpen() }
        }

        assertThat(result.abandoned).isTrue()
        assertThat(result.outcomes).isEmpty()
        assertThat(repo.failed).describedAs("no attempt burned on an open breaker (#4005)").isEmpty()
        assertThat(repo.sentBatches).isEmpty()
        assertThat(repo.rows.values.map { it.status }).containsOnly(OutboxStatus.DISPATCHING)
    }

    @Test
    fun `a CancellationException is rethrown and never recorded as a row failure — on both paths`() {
        val v1 = FakeRepo(listOf(entry("c.1"), entry("c.2")))
        val v2 = FakeRepoV2(listOf(entry("c.1"), entry("c.2")))

        runBlocking {
            val v1Job =
                async { OutboxDispatch.dispatchOnce(v1) { _ -> throw CancellationException("scope cancelled") } }
            assertThat(runCatching { v1Job.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            val v2Job =
                async { OutboxDispatch.dispatchOnce(v2) { _ -> throw CancellationException("scope cancelled") } }
            assertThat(runCatching { v2Job.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        }

        assertThat(v1.failed).describedAs("v1 path must not markFailed a cancellation").isEmpty()
        assertThat(v1.sent).isEmpty()
        assertThat(v2.failed).describedAs("v2 path must not markFailed a cancellation").isEmpty()
        assertThat(v2.sentBatches).isEmpty()
    }

    @Test
    fun `drain keeps claiming until a batch comes back short`() {
        val rows = (1..70).map { entry("d.$it") }
        val repo = FakeRepoV2(rows)

        val result = runBlocking {
            OutboxDispatch.drain(repo, batchSize = 25, budget = Duration.ofSeconds(30)) { _ -> yield() }
        }

        // 25 + 25 + 20 (short, but it dispatched — it may have promoted a head) + 0 → four
        // claims, all 70 rows dispatched in one tick.
        assertThat(repo.claims.get()).isEqualTo(4)
        assertThat(result.claimed).isEqualTo(70)
        assertThat(result.outcomes).hasSize(70)
        assertThat(repo.rows.values.map { it.status }).containsOnly(OutboxStatus.SENT)
    }

    @Test
    fun `drain sends a hot aggregate's whole backlog in one tick - a short batch that dispatched re-claims`() {
        val agg = UUID.randomUUID()
        val base = Instant.parse("2026-10-01T00:00:00Z")
        val rows = (1..3).map { i -> entry("hot.$i").copy(aggregateId = agg, createdAt = base.plusMillis(i.toLong())) }
        val repo = FakeRepoV2(rows)
        val order = mutableListOf<String>()

        runBlocking {
            OutboxDispatch.drain(repo, batchSize = 25, budget = Duration.ofSeconds(30)) { e -> order += e.eventType }
        }

        // Each claim sees only the aggregate's head; stopping on the first short batch sent ONE.
        assertThat(order).containsExactly("hot.1", "hot.2", "hot.3")
        assertThat(repo.claims.get()).isEqualTo(4)
    }

    @Test
    fun `drain stops on a short batch that dispatched nothing - everything left is parked`() {
        val repo = FakeRepoV2((1..3).map { entry("parked.$it") })

        runBlocking {
            OutboxDispatch.drain(repo, batchSize = 25, budget = Duration.ofSeconds(30)) { _ -> error("rejected") }
        }

        assertThat(repo.claims.get()).describedAs("no re-claim after an all-failed short batch").isEqualTo(1)
    }

    @Test
    fun `drain stops when the wall-clock budget is spent even while batches stay full`() {
        val rows = (1..500).map { entry("slow.$it") }
        val repo = FakeRepoV2(rows)

        val result = runBlocking {
            OutboxDispatch.drain(repo, batchSize = 10, budget = Duration.ofMillis(150)) { _ -> delay(60) }
        }

        assertThat(result.claimed).describedAs("more than one batch ran").isGreaterThan(10)
        assertThat(result.claimed).describedAs("but nowhere near the whole table").isLessThan(500)
        assertThat(repo.claims.get()).isLessThan(50)
    }

    @Test
    fun `drain stops after an abandoned batch instead of hammering an open breaker`() {
        val rows = (1..50).map { entry("cb.$it") }
        val repo = FakeRepoV2(rows)

        val result = runBlocking {
            OutboxDispatch.drain(repo, batchSize = 10, budget = Duration.ofSeconds(30)) { _ -> throw breakerOpen() }
        }

        assertThat(result.abandoned).isTrue()
        assertThat(repo.claims.get()).isEqualTo(1)
    }

    @Test
    fun `drain on a v1 repository is a single sequential batch per call`() {
        val rows = (1..5).map { entry("v1.$it") }
        val repo = FakeRepo(rows)
        val result = runBlocking { OutboxDispatch.dispatchOnce(repo, batchSize = 2) { _ -> } }
        assertThat(result.claimed).isEqualTo(2)
        assertThat(repo.sent).hasSize(2)
    }
}
