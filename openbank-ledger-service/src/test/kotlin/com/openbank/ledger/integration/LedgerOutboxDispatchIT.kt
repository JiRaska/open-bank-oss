// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.outbox.LedgerOutboxDispatcher
import com.openbank.ledger.infrastructure.persistence.entity.LedgerOutboxEntity
import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * End-to-end coverage of the reactive outbox dispatch chain (ADR-0050 / ADR-0049 D3) that the
 * pure-function unit tests in [com.openbank.ledger.infrastructure.outbox.LedgerOutboxDispatchTest]
 * cannot reach: a real reactive Panache session drives [LedgerOutboxDispatcher.dispatch] against
 * the dedicated IT Postgres, while the Kafka leg is swapped to the in-memory connector so we can
 * assert exactly what was produced.
 *
 * What this proves that the unit tests can't:
 *  - N1: the whole coroutine dispatch chain (`listProcessable` → publish → `markSent`) runs on
 *    the event loop without the HR000068 worker-thread failure.
 *  - N2: the produced record's key is the aggregate id (per-aggregate ordering).
 *  - N3: `ce-id` / `idempotency-key` headers carry the event id; `ce-type` carries the event type.
 *  - The row transitions PENDING → SENT (attemptCount incremented, sentAt set) after a successful
 *    publish — i.e. the persistence side of the chain actually commits.
 *
 * The `@Scheduled` tick is controlled via the poll interval in the test profile; dispatch is
 * driven explicitly here (subscribeAndAwait the suspend fun) and never races the assertions.
 */
@QuarkusTest
@QuarkusTestResource(LedgerOutboxDispatchIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.ledger.it.PostgresTestResource::class)
class LedgerOutboxDispatchIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("ledger-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dispatcher: LedgerOutboxDispatcher

    @Inject
    lateinit var repository: LedgerOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    // Reactive Panache must run on a Vert.x duplicated context; the JUnit thread is not one, so
    // every DB interaction is driven through VertxContextSupport.subscribeAndAwait.
    private fun persist(message: OutboxMessage) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction { repository.persistInTransaction(message) }
        }
    }

    private fun entityFor(eventId: UUID): LedgerOutboxEntity? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("eventId", eventId).firstResult() }
    }

    private fun headerValue(message: Message<String>, name: String): String {
        val md = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
        return String(md.headers.lastHeader(name).value(), StandardCharsets.UTF_8)
    }

    private fun key(message: Message<String>): String =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().key as String

    @Test
    fun `dispatch publishes pending rows with N2 key plus N3 headers and marks them SENT`() {
        // Two events for the SAME aggregate so we can assert per-aggregate key stability (N2).
        val aggregateId = UUID.randomUUID()
        val first = OutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = aggregateId,
            eventType = "ledger.journal.posted",
            payload = """{"seq":1}""",
            createdAt = Instant.now(),
        )
        val second = first.copy(
            eventId = UUID.randomUUID(),
            payload = """{"seq":2}""",
            createdAt = Instant.now().plusMillis(1),
        )
        persist(first)
        persist(second)

        // Drive the real coroutine dispatch chain once and wait for it to complete.
        // CoroutineScope(Dispatchers.Unconfined) starts on the Vert.x event loop that
        // subscribeAndAwait uses, so Panache.withSession{} avoids HR000068.
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { dispatcher.dispatch() }
        }

        val sink = connector.sink<String>("ledger-events-out")

        @Suppress("UNCHECKED_CAST")
        val received: List<Message<String>> = sink.received().map { it as Message<String> }
        val mine = received.filter { msg ->
            headerValue(msg, OutboxKafkaHeaders.HEADER_EVENT_ID) in
                setOf(first.eventId.toString(), second.eventId.toString())
        }
        assertThat(mine).hasSize(2)

        // N2: every record for this aggregate is keyed by the aggregate id.
        assertThat(mine.map { key(it) }).containsOnly(aggregateId.toString())

        // N3: ce-id / idempotency-key carry the event id; ce-type carries the event type.
        val byEventId = mine.associateBy {
            headerValue(it, OutboxKafkaHeaders.HEADER_EVENT_ID)
        }
        listOf(first, second).forEach { msg ->
            val produced = byEventId.getValue(msg.eventId.toString())
            assertThat(headerValue(produced, OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY))
                .isEqualTo(msg.eventId.toString())
            assertThat(headerValue(produced, OutboxKafkaHeaders.HEADER_EVENT_TYPE))
                .isEqualTo(msg.eventType)
            assertThat(produced.payload).isEqualTo(msg.payload)
        }

        // Persistence side committed: both rows are now SENT with a stamped sentAt and one attempt.
        listOf(first, second).forEach { msg ->
            val row = entityFor(msg.eventId)
            assertThat(row).isNotNull
            assertThat(row!!.status).isEqualTo("SENT")
            assertThat(row.sentAt).isNotNull
            assertThat(row.attemptCount).isEqualTo(1)
            assertThat(row.lastError).isNull()
        }

        // Nothing left for a subsequent tick to pick up.
        val remaining = VertxContextSupport.subscribeAndAwait { repository.listProcessableUni(50) }
        assertThat(remaining.map { it.eventId })
            .doesNotContain(first.eventId, second.eventId)
    }
}
