// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.messaging.KafkaLedgerOutboxEventPublisher
import com.openbank.ledger.infrastructure.outbox.LedgerOutboxDispatcher
import com.openbank.ledger.infrastructure.persistence.entity.LedgerOutboxEntity
import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.containers.PostgresTestResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Failure-path counterpart to [LedgerOutboxDispatchIT] (ADR-0050 N5 / ADR-0049 D3). The success
 * IT only proves the PENDING → SENT leg; this one drives the coroutine *recovery* leg that the
 * pure-function unit test on `statusAfterFailure(int)` cannot reach end-to-end: when
 * `publishWithResilience` fails, the dispatcher's catch path must actually COMMIT the
 * FAILED → … → DEAD transition — only a real reactive-session test proves it does not swallow it.
 *
 * The Kafka leg is a mockk double whose every send fails (simulated broker outage); the real
 * [LedgerOutboxRepositoryImpl] runs against the dedicated IT Postgres, so the row-state assertions
 * are the genuine committed DB state. The dispatcher is constructed directly with `dispatchEnabled=true`
 * so the guard does not suppress the explicit test-driven tick.
 */
@QuarkusTest
@QuarkusTestResource(LedgerOutboxDispatchFailureIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerOutboxDispatchFailureIT {

    // Keep app startup off a real broker; the mocked publisher never touches this channel, but the
    // outgoing channel still has to resolve at boot.
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("ledger-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var repository: LedgerOutboxRepositoryImpl

    private fun persist(message: OutboxMessage) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction { repository.persistInTransaction(message) }
        }
    }

    private fun entityFor(eventId: UUID): LedgerOutboxEntity = requireNotNull(
        VertxContextSupport.subscribeAndAwait {
            Panache.withSession { repository.find("eventId", eventId).firstResult() }
        },
    ) { "outbox row for $eventId not found" }

    private fun processableIds(): List<UUID> = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { repository.listProcessable(50).map { it.eventId } }
    }

    private fun setRetryAt(eventId: UUID, nextAttemptAt: Instant) {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) {
                Panache.withTransaction {
                    repository.update("nextAttemptAt = ?1 where eventId = ?2", nextAttemptAt, eventId)
                }.awaitSuspending()
            }
        }
    }

    // Drive one dispatch tick to completion.
    // CoroutineScope(Dispatchers.Unconfined) starts on the Vert.x event loop that
    // subscribeAndAwait uses, so Panache.withSession{} avoids HR000068.
    private fun tick(dispatcher: LedgerOutboxDispatcher) {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { dispatcher.dispatch() }
        }
    }

    // Start from an empty outbox: the @QuarkusTest Postgres is shared across test classes, and a
    // failing dispatcher would otherwise also drain rows other tests left behind.
    private fun clearOutbox() {
        VertxContextSupport.subscribeAndAwait { Panache.withTransaction { repository.deleteAll() } }
    }

    @Test
    fun `publish failure schedules a retry and parks the row DEAD at the attempt cap`() {
        clearOutbox()

        // A publisher whose every send fails — a broker outage on the money path.
        val failingPublisher = mockk<KafkaLedgerOutboxEventPublisher>()
        coEvery { failingPublisher.publish(any<OutboxEntry>()) } throws RuntimeException("simulated broker outage")
        // Construct directly with dispatchEnabled=true so the guard does not suppress the tick.
        val dispatcher = LedgerOutboxDispatcher(
            repository,
            failingPublisher,
            dispatchEnabled = true,
            metrics = mockk(relaxed = true),
            dispatchPollInterval = Duration.ofSeconds(5),
        )

        val msg = OutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "ledger.journal.posted",
            payload = """{"seq":1}""",
            createdAt = Instant.now(),
        )
        persist(msg)

        // First tick: publish fails → catch → markFailed commits FAILED (attempt 1).
        tick(dispatcher)
        entityFor(msg.eventId).let { row ->
            assertThat(row.status).isEqualTo("FAILED")
            assertThat(row.attemptCount).isEqualTo(1)
            assertThat(row.sentAt).isNull()
            assertThat(row.lastError).contains("simulated broker outage")
            assertThat(row.nextAttemptAt).isAfter(row.updatedAt)
            assertThat(row.claimToken).isNull()
        }
        setRetryAt(msg.eventId, Instant.now().plus(1, ChronoUnit.DAYS))
        assertThat(processableIds()).doesNotContain(msg.eventId)

        // Advance only the fixture's retry timestamp so each subsequent tick can reclaim it.
        repeat(LedgerOutboxRepositoryImpl.MAX_ATTEMPTS - 1) {
            setRetryAt(msg.eventId, Instant.EPOCH)
            tick(dispatcher)
        }
        entityFor(msg.eventId).let { row ->
            assertThat(row.status).isEqualTo("DEAD")
            assertThat(row.attemptCount).isEqualTo(LedgerOutboxRepositoryImpl.MAX_ATTEMPTS)
        }
        // DEAD rows drop out of the work set — the dispatcher never re-attempts them (ADR-0050 N5).
        assertThat(processableIds()).doesNotContain(msg.eventId)

        // The failure leg actually executed once per tick for OUR row — not silently skipped.
        coVerify(exactly = LedgerOutboxRepositoryImpl.MAX_ATTEMPTS) {
            failingPublisher.publish(match { it.eventId == msg.eventId })
        }
    }
}
