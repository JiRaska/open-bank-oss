// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
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
    ) : AbstractOutboxDispatcher() {
        suspend fun runBatch() = dispatchScheduledBatch()
    }

    private fun entry(type: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = type,
        payload = """{"t":"$type"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
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

    private fun domainMetrics(reg: SimpleMeterRegistry): DomainMetrics {
        val inst = mockk<Instance<io.micrometer.core.instrument.MeterRegistry>>()
        every { inst.isResolvable } returns true
        every { inst.get() } returns reg
        return DomainMetrics().apply { registryInstance = inst }
    }

    // Issue #5091 phase 1: metrics/service default to null so every existing dispatcher across
    // the fleet compiles and behaves unchanged; a concrete subclass opts in by overriding both.
    @Test
    fun `does not touch DomainMetrics when metrics and service are left at their null defaults`() {
        val rows = listOf(entry("account.created"))
        val repo = FakeRepo(rows)
        val publisher = FakePublisher()
        // TestOutboxDispatcher (above) overrides neither metrics nor service.
        val dispatcher = TestOutboxDispatcher(repo, publisher)

        runBlocking { dispatcher.runBatch() }

        // No crash, no NPE, dispatch behaves exactly as it did before this feature existed.
        assertThat(repo.sent).containsExactly(rows[0].eventId)
    }

    @Test
    fun `records openbank_outbox_dispatched_total, tagged by service and eventType, when opted in`() {
        val rows = listOf(entry("account.created"), entry("account.closed"))
        val repo = FakeRepo(rows)
        val publisher = FakePublisher()
        val reg = SimpleMeterRegistry()
        val dm = domainMetrics(reg)

        class MeteredDispatcher(
            override val outboxRepository: OutboxRepository,
            override val outboxEventPublisher: OutboxEventPublisher,
        ) : AbstractOutboxDispatcher() {
            override val metrics: DomainMetrics get() = dm
            override val service: String get() = "account"
            suspend fun runBatch() = dispatchScheduledBatch()
        }

        runBlocking { MeteredDispatcher(repo, publisher).runBatch() }

        assertThat(
            reg.find("openbank.outbox.dispatched").tag("service", "account").tag("topic", "account.created")
                .counter()?.count(),
        ).isEqualTo(1.0)
        assertThat(
            reg.find("openbank.outbox.dispatched").tag("service", "account").tag("topic", "account.closed")
                .counter()?.count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `does not record a dispatched metric for a row that fails to publish`() {
        val row = entry("payment.failed")
        val repo = FakeRepo(listOf(row))
        val publisher = FakePublisher()
        publisher.publishErrors[row.eventId] = IllegalStateException("kafka timeout")
        val reg = SimpleMeterRegistry()
        val dm = domainMetrics(reg)

        class MeteredDispatcher(
            override val outboxRepository: OutboxRepository,
            override val outboxEventPublisher: OutboxEventPublisher,
        ) : AbstractOutboxDispatcher() {
            override val metrics: DomainMetrics get() = dm
            override val service: String get() = "payments"
            suspend fun runBatch() = dispatchScheduledBatch()
        }

        runBlocking { MeteredDispatcher(repo, publisher).runBatch() }

        assertThat(reg.find("openbank.outbox.dispatched").counter()).isNull()
    }
}
