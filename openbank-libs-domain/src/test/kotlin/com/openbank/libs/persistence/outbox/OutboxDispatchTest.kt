// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

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

        assertThat(result.dispatchedCount).isEqualTo(2)
        assertThat(result.deadCount).isZero()
        assertThat(result.outcomes).allMatch { it is OutboxDispatchOutcome.Dispatched }
        assertThat(result.outcomes.map { it.entry.eventId }).containsExactly(rows[0].eventId, rows[1].eventId)
    }

    @Test
    fun `a failure below the DEAD threshold is reported non-terminal`() {
        // attemptCount=0 -> post-failure count is 1, well under DEFAULT_MAX_ATTEMPTS (10).
        val row = entry("retryable", attemptCount = 0)
        val repo = FakeRepo(listOf(row))

        val result = runBlocking { OutboxDispatch.dispatchOnce(repo) { error("kafka down") } }

        assertThat(result.dispatchedCount).isZero()
        assertThat(result.deadCount).isZero()
        val outcome = result.outcomes.single() as OutboxDispatchOutcome.Failed
        assertThat(outcome.terminal).isFalse()
    }

    @Test
    fun `a failure that exhausts the attempt budget is reported terminal (DEAD)`() {
        // attemptCount=9 -> post-failure count is 10 == DEFAULT_MAX_ATTEMPTS -> DEAD (ADR-0050 N5).
        val row = entry("poison", attemptCount = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1)
        val repo = FakeRepo(listOf(row))

        val result = runBlocking { OutboxDispatch.dispatchOnce(repo) { error("still failing") } }

        assertThat(result.dispatchedCount).isZero()
        assertThat(result.deadCount).isEqualTo(1)
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
        assertThat(result.dispatchedCount).isEqualTo(1)
        assertThat(result.deadCount).isEqualTo(1)
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
        assertThat(result.deadCount).isEqualTo(1)
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
        assertThat(result.dispatchedCount).isZero()
        assertThat(result.deadCount).isZero()
    }
}
