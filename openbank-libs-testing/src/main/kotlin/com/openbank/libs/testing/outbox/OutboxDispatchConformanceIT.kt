// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * End-to-end outbox dispatch conformance (ADR-0050 N1-N3, issue #467). Generalises
 * `openbank-ledger-service`'s `LedgerOutboxDispatchIT` — the only service with this coverage
 * before this kit, despite `AbstractOutboxDispatcher` (`openbank-libs-runtime`) already being the
 * shared dispatch-loop implementation every outbox-bearing service extends. That base class's own
 * `AbstractOutboxDispatcherTest` proves the *logic* (mark-sent-on-success, mark-failed-on-error)
 * against fakes; this proves the *real* reactive-Panache + Kafka wiring a fake can't reach:
 *
 *  - N1: the whole coroutine dispatch chain runs on the Vert.x event loop (no HR000068).
 *  - N2: the produced record's key is the aggregate id (per-aggregate ordering).
 *  - N3: `ce-id` / `idempotency-key` / `ce-type` headers are present and correct.
 *  - The row transitions PENDING → SENT (attemptCount incremented, sentAt set).
 *  - Replaying dispatch after a row is SENT does not re-publish it (idempotent replay).
 *
 * A concrete subclass wires the five abstract members below and carries its own `@QuarkusTest` +
 * `@QuarkusTestResource` annotations (this class itself is deliberately undecorated — CDI
 * injection and Testcontainers only activate on the concrete class, same constraint as every
 * other `*ConformanceTest` in this kit).
 *
 * ```
 * @QuarkusTest
 * @QuarkusTestResource(LedgerOutboxConformanceIT.InMemoryKafkaResource::class)
 * @QuarkusTestResource(PostgresTestResource::class)
 * class LedgerOutboxConformanceIT : OutboxDispatchConformanceIT() {
 *     @Inject lateinit var dispatcher: LedgerOutboxDispatcher
 *     @Inject lateinit var repository: LedgerOutboxRepositoryImpl
 *     @Inject @Connector("smallrye-in-memory") override lateinit var connector: InMemoryConnector
 *
 *     override val channelName = "ledger-events-out"
 *     override suspend fun seed(message: OutboxMessage) = repository.persistInTransaction(message)
 *     override suspend fun triggerDispatch() { dispatcher.dispatch() }
 *     override suspend fun findEntry(eventId: UUID) =
 *         repository.find("eventId", eventId).firstResult()?.toEntry()
 * }
 * ```
 */
// detekt's FunctionNaming excludes **/test/** by default, but these @Test methods must live in
// src/main so testImplementation(project(":openbank-libs-testing")) can pull and inherit them.
@Suppress("FunctionNaming")
abstract class OutboxDispatchConformanceIT {

    /** The channel this service's dispatcher publishes to (e.g. `"ledger-events-out"`). */
    protected abstract val channelName: String

    /** The service's in-memory Kafka connector (switched via `InMemoryConnector.switchOutgoingChannelsToInMemory`). */
    protected abstract val connector: InMemoryConnector

    /** Persist a PENDING row using the concrete service's own repository/entity mapping. */
    protected abstract suspend fun seed(message: OutboxMessage)

    /** Drive one dispatch cycle via the concrete service's own dispatcher bean. */
    protected abstract suspend fun triggerDispatch()

    /** Look up the row's current state (status/sentAt/attemptCount) by event id. */
    protected abstract suspend fun findEntry(eventId: UUID): OutboxEntry?

    /**
     * Reactive Panache needs a Vert.x duplicated context; the JUnit thread is not one. Every
     * concrete [seed]/[findEntry] implementation should run its Panache call through this.
     */
    protected fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun headerValue(message: Message<String>, name: String): String {
        val md = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
        return String((md.headers.lastHeader(name) ?: error("missing header $name")).value(), StandardCharsets.UTF_8)
    }

    private fun key(message: Message<String>): String =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().key as String

    @Suppress("UNCHECKED_CAST")
    private fun received(): List<Message<String>> =
        connector.sink<String>(channelName).received().map { it as Message<String> }

    @Test
    fun `dispatch publishes pending rows with a stable per-aggregate key plus CloudEvents headers, marks SENT`() {
        val aggregateId = Ids.newId()
        val first = OutboxMessage(
            aggregateId = aggregateId,
            eventType = "test.event.posted",
            payload = """{"seq":1}""",
            // createdAt DELIBERATELY omitted: the default is what ~48 fleet call sites use, and
            // passing it here is what made this suite blind to the epoch default (#3272).
        )
        val second = first.copy(eventId = Ids.newId(), payload = """{"seq":2}""")
        onEventLoop { seed(first) }
        onEventLoop { seed(second) }

        onEventLoop { triggerDispatch() }

        val mineIds = setOf(first.eventId.toString(), second.eventId.toString())
        val mine = received().filter {
            headerValue(it, OutboxKafkaHeaders.HEADER_EVENT_ID) in mineIds
        }
        assertThat(mine).hasSize(2)

        // N2: every record for this aggregate is keyed by the aggregate id.
        assertThat(mine.map { key(it) }).containsOnly(aggregateId.toString())

        // N3: ce-id / idempotency-key carry the event id; ce-type carries the event type.
        val byEventId = mine.associateBy { headerValue(it, OutboxKafkaHeaders.HEADER_EVENT_ID) }
        listOf(first, second).forEach { msg ->
            val produced = byEventId.getValue(msg.eventId.toString())
            assertThat(headerValue(produced, OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY))
                .isEqualTo(msg.eventId.toString())
            assertThat(headerValue(produced, OutboxKafkaHeaders.HEADER_EVENT_TYPE)).isEqualTo(msg.eventType)
            // NOT byte equality. A publisher may stamp `sourceService` onto the outgoing payload
            // (ledger/sdd/interest/fraud/delegation do, so audit-service records the producer's own
            // claim as AttributionSource.EVENT instead of deriving one from the topic name). What
            // this suite must guarantee is that relaying never LOSES or REWRITES a field the
            // producer wrote — an equality assertion cannot express that, and would force every
            // stamping module to fork the shared harness.
            val producedJson = PAYLOAD_MAPPER.readTree(produced.payload)
            val seededJson = PAYLOAD_MAPPER.readTree(msg.payload)
            seededJson.fieldNames().forEach { field ->
                assertThat(producedJson.get(field))
                    .describedAs("relay preserved producer field %s of %s", field, msg.eventId)
                    .isEqualTo(seededJson.get(field))
            }
            // The only addition the relay is permitted to make is attribution. Anything else is a
            // payload rewrite this suite exists to catch, and would pass silently once the equality
            // assertion above was relaxed.
            val added = producedJson.fieldNames().asSequence().toSet() -
                seededJson.fieldNames().asSequence().toSet()
            assertThat(added)
                .describedAs("relay added only attribution to %s", msg.eventId)
                .isSubsetOf(setOf("sourceService"))
        }

        // Persistence side committed: both rows are now SENT with a stamped sentAt and one attempt.
        listOf(first, second).forEach { msg ->
            val row = onEventLoop { findEntry(msg.eventId) }
            assertThat(row).describedAs("row for event ${msg.eventId}").isNotNull
            assertThat(row!!.status).isEqualTo(OutboxStatus.SENT)
            assertThat(row.attemptCount).isGreaterThanOrEqualTo(1)
            assertThat(row.lastError).isNull()
            // `isNotNull` is what this used to assert, and Instant.EPOCH satisfies it. The claim
            // query orders on created_at and the janitor prunes on updated_at, so a 1970 stamp is
            // a live defect, not a cosmetic one (#3272).
            assertThat(row.sentAt)
                .describedAs("sentAt must be a real time — markSent's default reaches every row in the fleet")
                .isNotNull
                .isAfter(EPOCH_SANITY_FLOOR)
            assertThat(row.createdAt)
                .describedAs("createdAt must be a real time when the caller omits it")
                .isAfter(EPOCH_SANITY_FLOOR)
        }
    }

    @Test
    fun `replaying dispatch after a row is SENT does not re-publish it`() {
        val message = OutboxMessage(
            aggregateId = Ids.newId(),
            eventType = "test.event.replay",
            payload = """{"once":true}""",
            createdAt = Instant.now(),
        )
        onEventLoop { seed(message) }
        onEventLoop { triggerDispatch() }

        val firstPassCount = received().count {
            headerValue(it, OutboxKafkaHeaders.HEADER_EVENT_ID) == message.eventId.toString()
        }
        assertThat(firstPassCount).isEqualTo(1)

        // A second dispatch tick must not pick this row up again — it's SENT, not PENDING/FAILED.
        onEventLoop { triggerDispatch() }

        val secondPassCount = received().count {
            headerValue(it, OutboxKafkaHeaders.HEADER_EVENT_ID) == message.eventId.toString()
        }
        assertThat(secondPassCount).describedAs("SENT row must not be re-published on replay").isEqualTo(1)
    }
    private companion object {
        /**
         * Payload comparison in this suite is STRUCTURAL, not textual — see the relay assertions.
         * A byte-equality assertion conflated two different guarantees ("no field was lost or
         * rewritten" and "nothing was added"), so a publisher that stamps attribution could not
         * satisfy it without forking the harness.
         */
        val PAYLOAD_MAPPER: ObjectMapper = ObjectMapper()

        /**
         * Any real stamp is after this; `Instant.EPOCH` is not. Deliberately a floor rather than
         * "within N seconds of now" — the point is to catch a 1970 default, not to police clock
         * skew on a slow runner (#3272).
         */
        val EPOCH_SANITY_FLOOR: Instant = Instant.parse("2020-01-01T00:00:00Z")
    }
}
