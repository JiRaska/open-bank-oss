// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.contract.message

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.sanctions.application.port.out.SanctionsChangePublisher
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.application.port.out.SanctionsPublicationOutcome
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.arc.Arc
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.ConnectorLiteral
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The messages sanctions-service's list-change publication puts on the wire, for the Pact
 * provider replay of the kyc-service contract (issue #15908).
 *
 * **Nothing here builds a message.** Each producer drives the production path end to end and
 * returns what came out of it:
 *
 *  1. rows are written to `sanctions_entries`, so the migration-installed trigger journals them;
 *  2. the real [SanctionsChangePublisher] turns the journal into an outbox row — this is the
 *     payload builder, and the only place the row's `event_type` is chosen;
 *  3. the real [SanctionsOutboxRepository] reads that row back as the entry the dispatcher sees;
 *  4. the real [OutboxEventPublisher] (the Kafka publisher) emits it to `sanctions-events-out`,
 *     which the `%test` profile binds to the in-memory connector;
 *  5. the payload and the Kafka HEADERS are read off the emitted record.
 *
 * So a producer that moves the event type into the body, renames it, or stops mapping it to
 * `ce-type` changes what this returns, and the replay goes red. A hand-assembled message would
 * move with the test instead of with the service — the reason the consumer defect in #15900
 * survived every gate.
 *
 * Why a separate class in its own package: pact-jvm finds `@PactVerifyProvider` methods by
 * scanning a package and instantiates the declaring class itself, outside the container. The
 * `@PactFolder` and `@PactBroker` twins both need these producers; declared on each, the same
 * description would have two definitions to keep identical. One definition here serves both
 * twins, and it reaches its collaborators through the container because nothing injects it.
 *
 * The scenario uses the `CZ_TERRORISTS` list because no migration seeds entries for it: the HTTP
 * interactions replayed by the same test class depend on the seeded lists staying untouched.
 * Everything written is removed again afterwards.
 */
class SanctionsListChangeMessageProducers {

    @PactVerifyProvider("a SANCTIONS_LIST_CHANGED event")
    fun listChanged(): MessageAndMetadata = published(SanctionsPublicationOutcome.PUBLISHED) {
        // Ten baseline entries, two of them touched: 20 % is under the storm threshold.
        seedBaseline(count = 10)
        execute("UPDATE sanctions_entries SET active = false WHERE external_id = 'pact-source-1'")
        execute("UPDATE sanctions_entries SET primary_name = 'Changed' WHERE external_id = 'pact-source-2'")
    }

    @PactVerifyProvider("a SANCTIONS_LIST_CHANGE_STORM event")
    fun listChangeStorm(): MessageAndMetadata = published(SanctionsPublicationOutcome.WITHHELD) {
        // The whole one-entry list changes at once: over the threshold, so publication is withheld.
        seedBaseline(count = 1)
        execute("UPDATE sanctions_entries SET primary_name = 'Changed' WHERE list_type = '$LIST'")
    }

    private fun published(expected: SanctionsPublicationOutcome, arrange: () -> Unit): MessageAndMetadata {
        val listId = onEventLoop {
            pool().preparedQuery("SELECT id FROM sanctions_lists WHERE list_type = $1")
                .execute(Tuple.of(LIST)).awaitSuspending().iterator().next().getUUID("id")
        }
        clear(listId)
        try {
            arrange()
            val outcome = onEventLoop { bean<SanctionsChangePublisher>().publishPending(listId, LIST_TYPE) }
            check(outcome == expected) { "publication answered $outcome, the scenario needs $expected" }
            // Selected by aggregate only, never by event type: the type is what is under test.
            val entry = onEventLoop {
                bean<SanctionsOutboxRepository>().listProcessable(OUTBOX_SCAN_LIMIT).single { it.aggregateId == listId }
            }
            onEventLoop { bean<OutboxEventPublisher>().publish(entry) }
            return emitted(entry.eventId)
        } finally {
            clear(listId)
        }
    }

    /** The record the Kafka publisher handed to the channel: payload verbatim, headers as sent. */
    @Suppress("UNCHECKED_CAST")
    private fun emitted(eventId: UUID): MessageAndMetadata {
        val connector = Arc.container()
            .instance(InMemoryConnector::class.java, ConnectorLiteral.of("smallrye-in-memory")).get()
        val message = connector.sink<String>(CHANNEL).received()
            .map { it as Message<String> }
            .last { headers(it)[OutboxKafkaHeaders.HEADER_EVENT_ID] == eventId.toString() }
        return MessageAndMetadata(message.payload.toByteArray(StandardCharsets.UTF_8), headers(message))
    }

    private fun headers(message: Message<String>): Map<String, String> =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().headers
            .associate { it.key() to String(it.value(), StandardCharsets.UTF_8) }

    private fun seedBaseline(count: Int) {
        execute(
            "INSERT INTO sanctions_entries (list_type, external_id, primary_name) " +
                "SELECT '$LIST', 'pact-source-' || n, 'Baseline ' || n FROM generate_series(1, $count) n",
        )
        // The baseline itself is not the change under test.
        execute("DELETE FROM sanctions_change_journal WHERE list_type = '$LIST'")
    }

    private fun clear(listId: UUID) {
        execute("DELETE FROM sanctions_entries WHERE list_type = '$LIST'")
        execute("DELETE FROM sanctions_change_journal WHERE list_type = '$LIST'")
        execute("DELETE FROM sanctions_outbox WHERE aggregate_id = '$listId'")
        execute(
            "UPDATE sanctions_change_publication SET last_storm_fingerprint = NULL, refresh_active = FALSE " +
                "WHERE list_type = '$LIST'",
        )
    }

    private fun execute(sql: String) {
        onEventLoop { pool().query(sql).execute().awaitSuspending() }
    }

    private fun pool(): PgPool = bean()

    private inline fun <reified T : Any> bean(): T = Arc.container().instance(T::class.java).get()

    /** Reactive Panache needs a Vert.x context; pact invokes producers on the bare test thread. */
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    companion object {
        /** Scanned by `MessageTestTarget` in both verification twins. */
        const val PACKAGE = "com.openbank.sanctions.contract.message"

        private val LIST_TYPE = SanctionsListType.CZ_TERRORISTS
        private val LIST = LIST_TYPE.name
        private const val CHANNEL = "sanctions-events-out"
        private const val OUTBOX_SCAN_LIMIT = 500
    }
}
