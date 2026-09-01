// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sdd.infrastructure.outbox.SddOutboxDispatcher
import com.openbank.sdd.infrastructure.persistence.entity.SddOutboxEntity
import com.openbank.sdd.infrastructure.persistence.repository.SddOutboxRepositoryImpl
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
 * End-to-end coverage of the reactive outbox dispatch chain (ADR-0050) that the pure-function
 * unit tests in [com.openbank.sdd.infrastructure.outbox.SddOutboxDispatchTest] cannot reach: a real
 * reactive Panache session drives [SddOutboxDispatcher.dispatch] against the dedicated IT Postgres,
 * while the Kafka leg is swapped to the in-memory connector so we can assert exactly what was produced.
 *
 * What this proves that the unit tests can't:
 *  - N1: the coroutine dispatch chain runs on the Vert.x event loop without HR000068 failures.
 *  - N2: the produced record's key is the aggregate id (per-mandate ordering).
 *  - N3: `ce-id` / `idempotency-key` headers carry the event id; `ce-type` carries the event type.
 *  - The row transitions PENDING → SENT (attemptCount incremented, sentAt set) after a successful
 *    publish — i.e. the persistence side of the chain actually commits.
 *
 * The `@Scheduled` tick is disabled in the `%test` profile while `dispatch-enabled=true` is set,
 * so [SddOutboxDispatcher.dispatch] is driven explicitly here and never races the assertions.
 */
@QuarkusTest
@QuarkusTestResource(SddOutboxDispatchIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sdd.it.PostgresTestResource::class)
class SddOutboxDispatchIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dispatcher: SddOutboxDispatcher

    @Inject
    lateinit var repository: SddOutboxRepositoryImpl

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
                val e = SddOutboxEntity().apply {
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

    private fun entityFor(eventId: UUID): SddOutboxEntity? = VertxContextSupport.subscribeAndAwait {
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
        // Two events for the SAME aggregate so we can assert per-mandate key stability (N2).
        val aggregateId = UUID.randomUUID()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val eventType = "sdd.collection.authorised.v1"
        persistPending(firstId, aggregateId, eventType, """{"seq":1}""", Instant.now())
        persistPending(secondId, aggregateId, eventType, """{"seq":2}""", Instant.now().plusMillis(1))

        // Drive the real reactive chain once and wait for it to complete.
        // CoroutineScope(Dispatchers.Unconfined) starts the coroutine on the Vert.x event loop
        // that subscribeAndAwait uses, so Panache.withSession{} inside dispatch() sees a valid
        // Vert.x context and avoids HR000068 (No current Vertx context found).
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { dispatcher.dispatch() }
        }

        val sink = connector.sink<String>("sdd-events-out")

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
            // NOT byte equality: KafkaSddOutboxEventPublisher stamps `sourceService` so audit-service
            // records this module's own claim (AttributionSource.EVENT) rather than deriving one from
            // the topic name. The relay must still never lose or rewrite a producer field, and must
            // add nothing but attribution — both of which byte equality conflated into one assertion.
            val mapper = ObjectMapper()
            val producedJson = mapper.readTree(produced.payload)
            val seededJson = mapper.readTree(payload)
            seededJson.fieldNames().forEach { field ->
                assertThat(producedJson.get(field)).isEqualTo(seededJson.get(field))
            }
            assertThat(producedJson.get("sourceService").asText()).isEqualTo("sdd-service")
            val added = producedJson.fieldNames().asSequence().toSet() -
                seededJson.fieldNames().asSequence().toSet()
            assertThat(added).isEqualTo(setOf("sourceService"))
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
            Panache.withSession {
                repository.find(
                    "status in (?1, ?2)",
                    OutboxStatus.PENDING.name,
                    OutboxStatus.FAILED.name,
                ).list()
            }
        }
        assertThat(remaining.map { it.eventId }).doesNotContain(firstId, secondId)
    }
}
