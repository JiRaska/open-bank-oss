// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AbstractOutboxDispatcherTest {

    /** Minimal in-memory repository recording the dispatcher's bookkeeping calls. */
    private class FakeRepo(private val rows: List<OutboxEntry>) : OutboxRepository {
        val sent = mutableListOf<UUID>()
        val failed = mutableListOf<Pair<UUID, String>>()
        override suspend fun listProcessable(limit: Int): List<OutboxEntry> = rows.take(limit)
        override suspend fun markSent(eventId: UUID, sentAt: Instant) {
            sent += eventId
        }
        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
            failed += eventId to error
        }
    }

    /** Minimal publisher tracking calls. */
    private class FakePublisher : OutboxEventPublisher {
        val published = mutableListOf<OutboxEntry>()
        val publishErrors = mutableMapOf<UUID, Exception>()

        override suspend fun publish(entry: OutboxEntry) {
            publishErrors[entry.eventId]?.let { throw it }
            published += entry
        }
    }

    /** Concrete dispatcher for testing (no @Scheduled, @Bulkhead, etc. — those belong on real subclasses).
     * Exposes [dispatchScheduledBatch] as a testable entry point — protected in the base class,
     * accessible here because TestOutboxDispatcher is a subclass. */
    private class TestOutboxDispatcher(
        override val outboxRepository: OutboxRepository,
        override val outboxEventPublisher: OutboxEventPublisher,
        service: String? = null,
    ) : AbstractOutboxDispatcher() {
        override val service: String = service ?: super.service
        suspend fun runBatch() = dispatchScheduledBatch()

        /** No CDI container in a plain unit test — set the field [AbstractOutboxDispatcher]
         * normally receives via `@Inject` (#5049). Only reachable from this subclass because
         * `metrics` is `protected`. */
        fun useMetrics(m: DomainMetrics) {
            metrics = m
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
    fun `dispatchScheduledBatch processes all entries and publishes them in order`() {
        val rows = listOf(entry("account.created"), entry("payment.sent"))
        val repo = FakeRepo(rows)
        val publisher = FakePublisher()
        val dispatcher = TestOutboxDispatcher(repo, publisher)

        runBlocking {
            dispatcher.runBatch()
        }

        // dispatcher passes the whole entry to publisher
        assertThat(publisher.published).containsExactlyElementsOf(rows)
        // repo marks sent after successful publish
        assertThat(repo.sent).containsExactly(rows[0].eventId, rows[1].eventId)
        assertThat(repo.failed).isEmpty()
    }

    @Test
    fun `marks entry failed when publish throws, does not mark sent`() {
        val row = entry("payment.failed")
        val repo = FakeRepo(listOf(row))
        val publisher = FakePublisher()
        val dispatcher = TestOutboxDispatcher(repo, publisher)

        // Inject a failure for this entry
        publisher.publishErrors[row.eventId] = IllegalStateException("kafka timeout")

        runBlocking {
            dispatcher.runBatch()
        }

        assertThat(publisher.published).isEmpty()
        assertThat(repo.sent).isEmpty()
        assertThat(repo.failed).hasSize(1)
        assertThat(repo.failed.single().first).isEqualTo(row.eventId)
        assertThat(repo.failed.single().second).isEqualTo("kafka timeout")
    }

    @Test
    fun `publishWithResilience can be overridden by concrete subclasses for CDI resilience annotations`() {
        val row = entry("account.updated")
        val repo = FakeRepo(listOf(row))
        val publisher = FakePublisher()

        // Concrete subclass that overrides publishWithResilience to track calls
        val publishCalls = mutableListOf<OutboxEntry>()
        class CustomDispatcher(
            override val outboxRepository: OutboxRepository,
            override val outboxEventPublisher: OutboxEventPublisher,
        ) : AbstractOutboxDispatcher() {
            override suspend fun publishWithResilience(entry: OutboxEntry) {
                publishCalls += entry
                super.publishWithResilience(entry)
            }
            suspend fun runBatch() = dispatchScheduledBatch()
        }

        val dispatcher = CustomDispatcher(repo, publisher)

        runBlocking {
            dispatcher.runBatch()
        }

        // custom override was called
        assertThat(publishCalls).containsExactly(row)
        // and it delegated to publisher
        assertThat(publisher.published).containsExactly(row)
        assertThat(repo.sent).containsExactly(row.eventId)
    }

    // ── #5049: outboxDispatched/outboxDead must actually fire, the right number of times ──

    @Test
    fun `with no metrics wired (plain unit-test construction), dispatch does not crash`() {
        // Every per-service *OutboxDispatcherTest in the fleet constructs its dispatcher exactly
        // this way — directly, with no CDI container, so `metrics` is never field-injected. This
        // is the regression this test exists to catch: a naive `metrics!!.outboxDispatched(...)`
        // would NPE every one of those ~34 tests the moment this class is touched.
        val row = entry("a.created")
        val repo = FakeRepo(listOf(row))
        val publisher = FakePublisher()
        val dispatcher = TestOutboxDispatcher(repo, publisher)

        runBlocking { dispatcher.runBatch() }

        assertThat(repo.sent).containsExactly(row.eventId)
    }

    @Test
    fun `dispatchScheduledBatch fires outboxDispatched once per successfully published row`() {
        val rows = listOf(entry("account.created"), entry("payment.sent"))
        val repo = FakeRepo(rows)
        val publisher = FakePublisher()
        val dispatcher = TestOutboxDispatcher(repo, publisher, service = "widget")
        val metrics = mockk<DomainMetrics>(relaxed = true)
        dispatcher.useMetrics(metrics)

        runBlocking { dispatcher.runBatch() }

        // Falsifying assertion: a no-op wiring (metrics never called) or a wiring that fires once
        // per BATCH instead of once per ROW both fail this — it must be exactly 2, tagged with
        // each row's own eventType as the topic label, not a shared/hardcoded one.
        verify(exactly = 1) { metrics.outboxDispatched("widget", "account.created") }
        verify(exactly = 1) { metrics.outboxDispatched("widget", "payment.sent") }
        verify(exactly = 0) { metrics.outboxDead(any()) }
    }

    @Test
    fun `dispatchScheduledBatch fires outboxDead only for rows that actually reach DEAD`() {
        val retrying = entry("retrying", attemptCount = 1)
        val dying = entry("dying", attemptCount = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1)
        val repo = FakeRepo(listOf(retrying, dying))
        val publisher = FakePublisher()
        publisher.publishErrors[retrying.eventId] = IllegalStateException("still failing")
        publisher.publishErrors[dying.eventId] = IllegalStateException("still failing")
        val dispatcher = TestOutboxDispatcher(repo, publisher, service = "widget")
        val metrics = mockk<DomainMetrics>(relaxed = true)
        dispatcher.useMetrics(metrics)

        runBlocking { dispatcher.runBatch() }

        // Falsifying assertion: a wiring that fires outboxDead for every FAILURE (not just the
        // terminal one) would call this twice; a wiring that never distinguishes DEAD at all
        // would call it zero times either way. Exactly 1 is the only wiring that agrees with
        // OutboxFailurePolicy's own threshold.
        verify(exactly = 1) { metrics.outboxDead("widget") }
        verify(exactly = 0) { metrics.outboxDispatched(any(), any()) }
    }

    @Test
    fun `service defaults to a kebab-case derivation of the concrete class name`() {
        assertThat(AbstractOutboxDispatcher.deriveServiceName("PartyOutboxDispatcher")).isEqualTo("party")
        assertThat(AbstractOutboxDispatcher.deriveServiceName("SepaPaymentOutboxDispatcher"))
            .isEqualTo("sepa-payment")
        assertThat(AbstractOutboxDispatcher.deriveServiceName("StandingOrderOutboxDispatcher"))
            .isEqualTo("standing-order")
    }
}
