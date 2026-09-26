// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.hibernate.reactive.mutiny.Mutiny
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Projects explicit transaction-to-payment and journal-to-transaction facts into the complaint lens. */
@ApplicationScoped
class PaymentBookingProjectionConsumer(
    private val sessions: Mutiny.SessionFactory,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.projection-generation") private val projectionGeneration: Long,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
    @ConfigProperty(name = "openbank.context.require-strict-revisions", defaultValue = "false")
    private val requireStrictRevisions: Boolean,
) {
    private val transactionLag = AtomicLong().also {
        meters.gauge(METRIC_LAG, io.micrometer.core.instrument.Tags.of("stream", TRANSACTION_STREAM), it)
    }
    private val ledgerLag = AtomicLong().also {
        meters.gauge(METRIC_LAG, io.micrometer.core.instrument.Tags.of("stream", LEDGER_STREAM), it)
    }

    @Incoming("transaction-events-in")
    suspend fun consumeTransaction(payload: String) = consume(payload, TRANSACTION_STREAM) { root ->
        if (root.text("eventType") != TRANSACTION_INITIATED) return@consume null
        require(root.text("sourceService") == TRANSACTION_SOURCE) { "unexpected transaction event source" }
        val transactionId = root.text("aggregateId")
        val reversalOf = root.text("reversalOf")
        if (reversalOf.isNotEmpty()) {
            require(root.text("type") == "REVERSAL" && isUuid(transactionId) && isUuid(reversalOf)) {
                "transaction reversal event has invalid source identity"
            }
            val version = root.requiredVersion(requireStrictRevisions)
            val occurredAt = Instant.parse(root.text("occurredAt"))
            return@consume BookingProjectionEvent.reversal(reversalOf, transactionId, version, occurredAt)
        }
        val paymentId = root.text("originatingPaymentId")
        if (paymentId.isEmpty()) return@consume null
        val version = root.requiredVersion(requireStrictRevisions)
        val occurredAt = Instant.parse(root.text("occurredAt"))
        require(isUuid(paymentId) && isUuid(transactionId)) { "transaction event misses payment correlation" }
        BookingProjectionEvent.transaction(paymentId, transactionId, version, occurredAt)
    }

    @Incoming("ledger-events-in")
    suspend fun consumeLedger(payload: String) = consume(payload, LEDGER_STREAM) { root ->
        if (root.text("eventType") != JOURNAL_POSTED) return@consume null
        require(root.text("sourceService") == LEDGER_SOURCE) { "unexpected ledger event source" }
        val journalId = root.text("aggregateId")
        val transactionId = root.text("transactionId")
        val entryDate = root.text("entryDate")
        val version = root.requiredVersion(requireStrictRevisions)
        val occurredAt = Instant.parse(root.text("occurredAt"))
        require(isUuid(journalId) && isUuid(transactionId) && entryDate.isNotEmpty()) {
            "ledger event misses booking identity"
        }
        BookingProjectionEvent.ledger(journalId, transactionId, entryDate, version, occurredAt)
    }

    private suspend fun consume(payload: String, stream: String, parse: (JsonNode) -> BookingProjectionEvent?) {
        val started = System.nanoTime()
        try {
            runCatching {
                require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) {
                    "$stream event exceeds $MAX_EVENT_BYTES bytes"
                }
                val event = parse(objectMapper.readTree(payload))
                if (event == null) {
                    meters.counter(METRIC_EVENTS, "stream", stream, "outcome", "ignored").increment()
                    return
                }
                sessions.withTransaction { session, _ ->
                    session.createNativeQuery(
                        "select set_config('statement_timeout', :timeout, true)",
                        String::class.java,
                    )
                        .setParameter("timeout", "${timeoutMs}ms").singleResult
                        .flatMap { project(session, event) }
                }
                    .ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
                meters.counter(METRIC_EVENTS, "stream", stream, "outcome", "projected").increment()
                val lag = if (stream == TRANSACTION_STREAM) transactionLag else ledgerLag
                lag.set((clock.instant().epochSecond - event.occurredAt.epochSecond).coerceAtLeast(0))
            }.onFailure {
                meters.counter(METRIC_EVENTS, "stream", stream, "outcome", "failed").increment()
            }.getOrThrow()
        } finally {
            meters.timer(METRIC_DURATION, "stream", stream)
                .record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun project(session: Mutiny.Session, event: BookingProjectionEvent): Uni<Void> =
        GraphNodeHistoryWriter.append(
            session,
            bankScope,
            projectionGeneration,
            event.eventKey,
            event.aggregateRef,
            listOf(event.fromNode.observation(false), event.toNode.observation(true)),
            clock.instant(),
        ).flatMap {
            GraphEdgeHistoryWriter.append(
                session,
                bankScope,
                projectionGeneration,
                event.eventKey,
                event.aggregateRef,
                listOf(
                    GraphEdgeObservation(
                        event.fromNode.key,
                        event.toNode.key,
                        event.relation,
                        event.source,
                        event.occurredAt,
                        event.version,
                    ),
                ),
                clock.instant(),
            )
        }.flatMap {
            mutation(
                session,
                """INSERT INTO context_projection_events
                    (bank_scope, projection_generation, event_key, source_system, aggregate_ref, source_version, occurred_at, processed_at)
                    VALUES (:bankScope, :generation, :eventKey, :source, :aggregateRef, :version, :occurredAt, :processedAt)
                    ON CONFLICT (bank_scope, projection_generation, event_key) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "bankScope" to bankScope,
                    "generation" to projectionGeneration,
                    "eventKey" to event.eventKey,
                    "source" to event.source,
                    "aggregateRef" to event.aggregateRef,
                    "version" to event.version,
                    "occurredAt" to event.occurredAt,
                    "processedAt" to clock.instant(),
                ),
            )
        }.flatMap { inserted ->
            if (inserted == 0) {
                Uni.createFrom().voidItem()
            } else {
                upsertPlaceholderNode(session, event.fromNode)
                    .flatMap { upsertEvidenceNode(session, event.toNode) }
                    .flatMap { insertEdge(session, event) }
                    .replaceWithVoid()
            }
        }

    /** A placeholder makes projection order irrelevant; its owning source replaces it later. */
    private fun upsertPlaceholderNode(session: Mutiny.Session, node: BookingNode): Uni<Int> = mutation(
        session,
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system,
             source_ref, display_label, classification, valid_from, valid_to, recorded_at, source_version)
            VALUES (:rowId, :key, :bankScope, :generation, 'COMPLAINT', :type, :source,
                    :sourceRef, :label, 'RESTRICTED', :validFrom, NULL, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, node_key) DO NOTHING
        """.trimIndent(),
        node.values(bankScope, projectionGeneration, clock.instant()),
    )

    private fun upsertEvidenceNode(session: Mutiny.Session, node: BookingNode): Uni<Int> = mutation(
        session,
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system,
             source_ref, display_label, classification, valid_from, valid_to, recorded_at, source_version)
            VALUES (:rowId, :key, :bankScope, :generation, 'COMPLAINT', :type, :source,
                    :sourceRef, :label, 'RESTRICTED', :validFrom, NULL, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, node_key) DO UPDATE SET
              node_type = EXCLUDED.node_type, source_system = EXCLUDED.source_system,
              source_ref = EXCLUDED.source_ref, display_label = EXCLUDED.display_label,
              valid_from = EXCLUDED.valid_from, recorded_at = EXCLUDED.recorded_at,
              source_version = EXCLUDED.source_version
            WHERE context_nodes.source_system <> EXCLUDED.source_system
               OR context_nodes.source_version < EXCLUDED.source_version
        """.trimIndent(),
        node.values(bankScope, projectionGeneration, clock.instant()),
    )

    private fun insertEdge(session: Mutiny.Session, event: BookingProjectionEvent): Uni<Int> = mutation(
        session,
        """INSERT INTO context_edges
            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type,
             source_system, evidence_ref, valid_from, valid_to, recorded_at, source_version, retained_history_from)
            VALUES (:id, :bankScope, :generation, 'COMPLAINT', :fromKey, :toKey, :relation,
                    :source, :evidenceRef, :validFrom, NULL, :recordedAt, :version, :validFrom)
            ON CONFLICT (bank_scope, projection_generation, edge_id) DO UPDATE SET
              retained_history_from = LEAST(context_edges.retained_history_from, EXCLUDED.retained_history_from)
        """.trimIndent(),
        mapOf(
            "id" to stableId("COMPLAINT|${event.fromNode.key}|${event.toNode.key}|${event.relation}"),
            "bankScope" to bankScope,
            "generation" to projectionGeneration,
            "fromKey" to event.fromNode.key,
            "toKey" to event.toNode.key,
            "relation" to event.relation,
            "source" to event.source,
            "evidenceRef" to event.eventKey,
            "validFrom" to event.occurredAt,
            "recordedAt" to clock.instant(),
            "version" to event.version,
        ),
    )

    private fun mutation(session: Mutiny.Session, sql: String, values: Map<String, Any>): Uni<Int> {
        val query = session.createNativeMutationQuery(sql)
        values.forEach { (name, value) -> query.setParameter(name, value) }
        return query.executeUpdate()
    }

    private companion object {
        const val TRANSACTION_INITIATED = "TransactionInitiated"
        const val JOURNAL_POSTED = "JournalPosted"
        const val TRANSACTION_SOURCE = "transaction-service"
        const val LEDGER_SOURCE = "ledger-service"
        const val TRANSACTION_STREAM = "transaction"
        const val LEDGER_STREAM = "ledger"
        const val MAX_EVENT_BYTES = 64 * 1024
        const val METRIC_EVENTS = "openbank_context_projection_events_total"
        const val METRIC_LAG = "openbank_context_projection_lag_seconds"
        const val METRIC_DURATION = "openbank_context_projection_duration"
    }
}

private fun JsonNode.requiredVersion(requireStrict: Boolean): Long {
    val field = path("version")
    require(!requireStrict || field.isIntegralNumber) { "event has no strict aggregate version" }
    return field.takeIf { it.isIntegralNumber }?.asLong()?.also {
        require(it >= 0) { "event version must be non-negative" }
    } ?: Instant.parse(text("occurredAt")).toEpochMilli()
}

private fun JsonNode.text(name: String): String = path(name).takeIf { it.isTextual }?.asText()?.trim().orEmpty()
private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
private fun stableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

private data class BookingNode(
    val key: String,
    val type: String,
    val source: String,
    val sourceRef: String,
    val label: String,
    val validFrom: Instant,
    val version: Long,
) {
    fun values(bankScope: String, generation: Long, recordedAt: Instant): Map<String, Any> = mapOf(
        "rowId" to UUID.nameUUIDFromBytes("$bankScope|$generation|$key".toByteArray(StandardCharsets.UTF_8)),
        "key" to key,
        "bankScope" to bankScope,
        "generation" to generation,
        "type" to type,
        "source" to source,
        "sourceRef" to sourceRef,
        "label" to label,
        "validFrom" to validFrom,
        "recordedAt" to recordedAt,
        "version" to version,
    )
}

private fun BookingNode.observation(authoritative: Boolean) = GraphNodeObservation(
    key,
    type,
    source,
    sourceRef,
    label,
    validFrom,
    version,
    authoritative,
)

private data class BookingProjectionEvent(
    val eventKey: String,
    val aggregateRef: String,
    val source: String,
    val version: Long,
    val occurredAt: Instant,
    val fromNode: BookingNode,
    val toNode: BookingNode,
    val relation: String,
) {
    companion object {
        fun reversal(originalId: String, reversalId: String, version: Long, at: Instant) = BookingProjectionEvent(
            "transaction:$reversalId:$version",
            "booking-transaction:$reversalId",
            "transaction-service",
            version,
            at,
            BookingNode(
                "booking-transaction:$originalId",
                "TRANSACTION_BOOKING",
                "transaction-service",
                originalId,
                "Original booking",
                at,
                version,
            ),
            BookingNode(
                "booking-transaction:$reversalId",
                "TRANSACTION_BOOKING",
                "transaction-service",
                reversalId,
                "Reversal booking initiated",
                at,
                version,
            ),
            "REVERSED_BY",
        )

        fun transaction(paymentId: String, transactionId: String, version: Long, at: Instant) = BookingProjectionEvent(
            "transaction:$transactionId:$version",
            "booking-transaction:$transactionId",
            "transaction-service",
            version,
            at,
            BookingNode(
                "transaction:$paymentId",
                "PAYMENT",
                "transaction-service",
                paymentId,
                "Payment awaiting booking evidence",
                at,
                version,
            ),
            BookingNode(
                "booking-transaction:$transactionId",
                "TRANSACTION_BOOKING",
                "transaction-service",
                transactionId,
                "Booking transaction initiated",
                at,
                version,
            ),
            "BOOKING_REQUESTED",
        )

        fun ledger(journalId: String, transactionId: String, entryDate: String, version: Long, at: Instant) =
            BookingProjectionEvent(
                "ledger:$journalId:$version",
                "ledger-booking:$journalId",
                "ledger-service",
                version,
                at,
                BookingNode(
                    "booking-transaction:$transactionId",
                    "TRANSACTION_BOOKING",
                    "ledger-service",
                    transactionId,
                    "Booking transaction",
                    at,
                    version,
                ),
                BookingNode(
                    "ledger-booking:$journalId",
                    "LEDGER_BOOKING",
                    "ledger-service",
                    journalId,
                    "Ledger journal posted · $entryDate",
                    at,
                    version,
                ),
                "BOOKED_AS",
            )
    }
}
