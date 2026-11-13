// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.contract.message

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.sanctions.application.port.`in`.MonitoredSubjectRequest
import com.openbank.sanctions.application.port.`in`.SanctionsMonitoringUseCase
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.application.usecase.SanctionsMonitoringService
import com.openbank.sanctions.contract.MonitoringPactStates
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
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
import java.time.Instant
import java.util.UUID

/**
 * The finding sanctions-service puts on the wire for a monitored subject, for the Pact provider
 * replay of the kyb-service contract (ADR-0376 D6).
 *
 * **Nothing here builds a message.** The producer drives the production path end to end and
 * returns what came out of it:
 *
 *  1. the subject is registered through the real [SanctionsMonitoringUseCase], as kyb-service's
 *     own client would register it;
 *  2. a list entry with the subject's name is ingested through the importer's own upsert, so the
 *     migration-installed triggers journal it and queue it for monitoring;
 *  3. the real [SanctionsMonitoringService] delta run matches the entry against the registry and
 *     writes the finding with its outbox row — the only place the payload and the row's
 *     `event_type` are chosen;
 *  4. the real [SanctionsOutboxRepository] reads that row back as the entry the dispatcher sees;
 *  5. the real [OutboxEventPublisher] emits it to `sanctions-events-out`, which the `%test`
 *     profile binds to the in-memory connector;
 *  6. the payload and the Kafka HEADERS are read off the emitted record.
 *
 * So a producer that moves the event type into the body, renames it, stops mapping it to
 * `ce-type`, or drops a field the consumer reads changes what this returns, and the replay goes
 * red. The switch that gates the delta run is turned on by the interaction's provider state
 * ([MonitoringPactStates.enableMonitoring]), which pact-jvm runs before it asks for the message.
 *
 * In the producers' package so the `MessageTestTarget` both verification twins already scan finds
 * it, and reaching its collaborators through the container because pact-jvm instantiates the
 * class itself. Every name and identifier is invented, and everything written is removed again.
 */
class MonitoredSubjectMatchedMessageProducers {

    @PactVerifyProvider("a MONITORED_SUBJECT_MATCHED event")
    fun monitoredSubjectMatched(): MessageAndMetadata {
        clear()
        try {
            onEventLoop {
                bean<SanctionsMonitoringUseCase>().register(
                    MonitoringPactStates.KYB_CLIENT,
                    MonitoringPactStates.MATCHED_REF,
                    MonitoredSubjectRequest(entityType = EntityType.INDIVIDUAL, name = LISTED_NAME),
                )
            }
            ingest()
            val outcome = onEventLoop { bean<SanctionsMonitoringService>().runDelta() }
            check(outcome == MonitoringRunOutcome.COMPLETED) { "the delta run answered $outcome" }
            val findingId = onEventLoop {
                pool().preparedQuery(
                    "SELECT f.id FROM sanctions_monitoring_findings f " +
                        "JOIN sanctions_monitored_subjects s ON s.id = f.subject_id WHERE s.subject_ref = $1",
                ).execute(Tuple.of(MonitoringPactStates.MATCHED_REF)).awaitSuspending().map { it.getUUID(0) }
            }.single()
            // Selected by aggregate only, never by event type: the type is what is under test.
            val entry = onEventLoop {
                bean<SanctionsOutboxRepository>().listProcessable(OUTBOX_SCAN_LIMIT).single { it.aggregateId == findingId }
            }
            onEventLoop { bean<OutboxEventPublisher>().publish(entry) }
            return emitted(entry.eventId)
        } finally {
            clear()
        }
    }

    /** One entry carrying the subject's name, written the way the importer writes it. */
    private fun ingest() {
        val now = Instant.now()
        onEventLoop {
            bean<SanctionsEntryRepositoryImpl>().upsertAll(
                listOf(
                    SanctionsEntry(
                        listType = SanctionsListType.OFAC_SDN,
                        externalId = ENTRY_ID,
                        entityType = EntityType.INDIVIDUAL,
                        primaryName = LISTED_NAME,
                        aliases = emptyList(),
                        searchText = LISTED_NAME.lowercase(),
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
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

    private fun clear() {
        // The outbox rows first: they are found through the findings the subject's deletion cascades to.
        execute(
            "DELETE FROM sanctions_outbox WHERE aggregate_id IN (SELECT f.id FROM sanctions_monitoring_findings f " +
                "JOIN sanctions_monitored_subjects s ON s.id = f.subject_id " +
                "WHERE s.subject_ref = '${MonitoringPactStates.MATCHED_REF}')",
        )
        execute(
            "DELETE FROM sanctions_monitored_subjects WHERE owner_service = '${MonitoringPactStates.KYB_CLIENT}' " +
                "AND subject_ref = '${MonitoringPactStates.MATCHED_REF}'",
        )
        execute("DELETE FROM sanctions_monitoring_pending WHERE external_id = '$ENTRY_ID'")
        execute("DELETE FROM sanctions_entries WHERE external_id = '$ENTRY_ID'")
        execute("DELETE FROM sanctions_change_journal WHERE external_id = '$ENTRY_ID'")
    }

    private fun execute(sql: String) {
        onEventLoop { pool().query(sql).execute().awaitSuspending() }
    }

    private fun pool(): PgPool = bean()

    private inline fun <reified T : Any> bean(): T = Arc.container().instance(T::class.java).get()

    /** Reactive Panache needs a Vert.x context; pact invokes producers on the bare test thread. */
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private companion object {
        /** Invented, and sharing no trigram with any seeded entry. */
        const val LISTED_NAME = "Zorvath Quellinbrook"
        const val ENTRY_ID = "pact-monitoring-entry-1"
        const val CHANNEL = "sanctions-events-out"
        const val OUTBOX_SCAN_LIMIT = 500
    }
}
