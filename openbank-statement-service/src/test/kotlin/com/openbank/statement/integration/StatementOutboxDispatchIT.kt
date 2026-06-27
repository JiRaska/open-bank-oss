// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.integration

import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.statement.infrastructure.outbox.StatementOutboxDispatcher
import com.openbank.statement.infrastructure.persistence.entity.StatementOutboxEntity
import com.openbank.statement.infrastructure.persistence.repository.StatementOutboxRepositoryImpl
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * End-to-end coverage of the suspend-based outbox dispatch chain (ADR-0050 / ADR-0049 D3) that
 * the pure-function unit tests in [com.openbank.statement.infrastructure.outbox.StatementOutboxDispatchTest]
 * cannot reach: a real reactive Panache session drives [StatementOutboxDispatcher.dispatch] against
 * the dedicated IT Postgres, while the Kafka leg is swapped to the in-memory connector so we can
 * assert exactly what was produced.
 *
 * What this proves that the unit tests can't:
 *  - N1: the whole suspend chain (`listProcessable` → publish → `markSent`) runs on the event loop
 *    without the HR000068 worker-thread failure.
 *  - N2: the produced record's key is the aggregate id (per-aggregate ordering).
 *  - N3: `ce-id` / `idempotency-key` headers carry the event id; `ce-type` carries the event type.
 *  - The row transitions PENDING → SENT (attemptCount incremented, sentAt set) after a successful
 *    publish — i.e. the persistence side of the chain actually commits.
 *
 * The `@Scheduled` tick is disabled in the `%test` profile (`openbank.outbox.dispatch-enabled=false`),
 * so dispatch is driven explicitly here and never races the assertions.
 * [StatementOutboxDispatcher.dispatch] is called directly with enabled overridden to true via the
 * test application properties supplied by [DispatchEnabledResource].
 */
@QuarkusTest
@QuarkusTestResource(StatementOutboxDispatchIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.statement.it.PostgresTestResource::class)
@QuarkusTestResource(StatementOutboxDispatchIT.DispatchEnabledResource::class)
class StatementOutboxDispatchIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        // Swap BOTH Kafka legs to in-memory so booting the full app under test needs no broker:
        // the outgoing outbox leg (asserted below) and the incoming account-events-in leg that feeds
        // the AccountRegistry projection (irrelevant to this test, but must not dial a real broker).
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("statement-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("account-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    /** Enables the dispatch gate for the duration of this test. */
    class DispatchEnabledResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "true")

        override fun stop() = Unit
    }

    @Inject
    lateinit var dispatcher: StatementOutboxDispatcher

    @Inject
    lateinit var repository: StatementOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    // Reactive Panache must run on a Vert.x duplicated context; the JUnit thread is not one, so
    // every DB interaction is driven through VertxContextSupport.subscribeAndAwait.
    private fun persistPending(
        eventId: UUID,
        aggregateId: UUID,
        eventType: String,
        payload: String,
        createdAt: Instant,
    ) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                val e = StatementOutboxEntity().apply {
                    this.eventId = eventId
                    this.aggregateId = aggregateId
                    this.eventType = eventType
                    this.payload = payload
                    this.status = OutboxStatus.PENDING.name
                    this.attemptCount = 0
                    this.createdAt = createdAt
                    this.updatedAt = createdAt
                }
                repository.persist(e)
            }
        }
    }

    private fun entityFor(eventId: UUID): StatementOutboxEntity? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("eventId", eventId).firstResult() }
    }

    private fun headerValue(message: Message<String>, name: String): String = String(
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
            .headers.lastHeader(name).value(),
        StandardCharsets.UTF_8,
    )

    private fun key(message: Message<String>): String =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().key as String

    @Test
    fun `dispatch publishes pending rows with N2 key plus N3 headers and marks them SENT`() {
        // Two events for the SAME aggregate so we can assert per-aggregate key stability (N2).
        val aggregateId = UUID.randomUUID()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val eventType = "account.statement.period.closed.v1"
        persistPending(firstId, aggregateId, eventType, """{"seq":1}""", Instant.now())
        persistPending(secondId, aggregateId, eventType, """{"seq":2}""", Instant.now().plusMillis(1))

        // Drive the suspend dispatch chain once and wait for it to complete.
        VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async { dispatcher.dispatch() }.asUni()
        }

        val sink = connector.sink<String>("statement-events-out")

        @Suppress("UNCHECKED_CAST")
        val received: List<Message<String>> = sink.received().map { it as Message<String> }
        val mine = received.filter { msg ->
            headerValue(msg, OutboxKafkaHeaders.HEADER_EVENT_ID) in
                setOf(firstId.toString(), secondId.toString())
        }
        assertThat(mine).hasSize(2)

        // N2: every record for this aggregate is keyed by the aggregate id.
        assertThat(mine.map { key(it) }).containsOnly(aggregateId.toString())

        // N3: ce-id / idempotency-key carry the event id; ce-type carries the event type.
        val byEventId = mine.associateBy {
            headerValue(it, OutboxKafkaHeaders.HEADER_EVENT_ID)
        }
        mapOf(firstId to """{"seq":1}""", secondId to """{"seq":2}""").forEach { (id, payload) ->
            val produced = byEventId.getValue(id.toString())
            assertThat(headerValue(produced, OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY))
                .isEqualTo(id.toString())
            assertThat(headerValue(produced, OutboxKafkaHeaders.HEADER_EVENT_TYPE))
                .isEqualTo(eventType)
            assertThat(produced.payload).isEqualTo(payload)
        }

        // Persistence side committed: both rows are now SENT with a stamped sentAt and one attempt.
        listOf(firstId, secondId).forEach { id ->
            val row = entityFor(id)
            assertThat(row).isNotNull
            assertThat(row!!.status).isEqualTo(OutboxStatus.SENT.name)
            assertThat(row.sentAt).isNotNull
            assertThat(row.attemptCount).isEqualTo(1)
            assertThat(row.lastError).isNull()
        }

        // Nothing left for a subsequent tick to pick up.
        val remaining = VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async { repository.listProcessable(50) }.asUni()
        }
        assertThat(remaining.map { it.eventId }).doesNotContain(firstId, secondId)
    }
}
