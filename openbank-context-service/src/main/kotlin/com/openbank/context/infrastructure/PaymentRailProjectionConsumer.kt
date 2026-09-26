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

/** Projects source-backed clearing acknowledgement and SEPA return evidence into the complaint lens. */
@ApplicationScoped
class PaymentRailProjectionConsumer(
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
    private val clearingLag = lagGauge(CLEARING_STREAM)
    private val sepaReturnLag = lagGauge(SEPA_RETURN_STREAM)

    @Incoming("clearing-events-in")
    suspend fun consumeClearing(payload: String) = consume(payload, CLEARING_STREAM) { root ->
        if (root.railText("eventType") != ITEM_CLEARED) return@consume null
        require(root.railText("sourceService") == CLEARING_SOURCE) { "unexpected clearing event source" }
        val paymentId = root.railText("paymentId")
        val itemId = root.railText("itemId")
        val batchId = root.railText("batchId")
        val status = root.railText("status")
        val version = root.railVersion(requireStrictRevisions)
        val occurredAt = Instant.parse(root.railText("occurredAt"))
        require(
            railUuid(paymentId) && railUuid(itemId) && railUuid(batchId) && status == "SETTLED" && version > 0,
        ) {
            "clearing event misses settled item identity"
        }
        RailProjectionEvent.clearing(paymentId, itemId, batchId, version, occurredAt)
    }

    @Incoming("sepa-payment-events-in")
    suspend fun consumeSepaReturn(payload: String) = consume(payload, SEPA_RETURN_STREAM) { root ->
        if (root.railText("eventType") != SEPA_RETURNED) return@consume null
        require(root.railText("sourceService") == SEPA_SOURCE) { "unexpected SEPA return source" }
        val paymentId = root.railText("paymentId")
        val reason = root.railText("returnReasonCode").takeIf(RETURN_REASON::matches)
        val reversal = root.path("reversalPerformed").takeIf { it.isBoolean }?.asBoolean()
        val reversalTransactionId = root.path("reversalTransactionId")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.let {
                require(it.isTextual) { "invalid reversal transaction identity" }
                it.asText()
            }
        val version = root.railVersion(requireStrictRevisions)
        val occurredAt = Instant.parse(root.railText("occurredAt"))
        require(railUuid(paymentId) && reversal != null && version > 0) {
            "SEPA return event misses evidence identity"
        }
        require(reversalTransactionId == null || (reversal && railUuid(reversalTransactionId))) {
            "SEPA return event has an unsupported reversal identity"
        }
        RailProjectionEvent.sepaReturn(paymentId, reason, reversal, reversalTransactionId, version, occurredAt)
    }

    private suspend fun consume(payload: String, stream: String, parse: (JsonNode) -> RailProjectionEvent?) {
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
                ContextSqlOperation.execute(sessions, timeoutMs) { operation ->
                    operation.sql { session ->
                        session.createNativeQuery(
                            "select set_config('openbank.bank_scope', :bank, true)",
                            String::class.java,
                        )
                            .setParameter("bank", bankScope).singleResult
                    }.flatMap { project(operation, event) }
                }.awaitSuspending()
                meters.counter(METRIC_EVENTS, "stream", stream, "outcome", "projected").increment()
                (if (stream == CLEARING_STREAM) clearingLag else sepaReturnLag)
                    .set((clock.instant().epochSecond - event.occurredAt.epochSecond).coerceAtLeast(0))
            }.onFailure {
                meters.counter(METRIC_EVENTS, "stream", stream, "outcome", "failed").increment()
            }.getOrThrow()
        } finally {
            meters.timer(METRIC_DURATION, "stream", stream)
                .record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun project(operation: ContextSqlOperation, event: RailProjectionEvent): Uni<Void> =
        GraphNodeHistoryWriter.append(
            operation,
            bankScope,
            projectionGeneration,
            event.eventKey,
            event.aggregateRef,
            event.nodes.map { it.observation() },
            clock.instant(),
        ).flatMap {
            GraphEdgeHistoryWriter.append(
                operation,
                bankScope,
                projectionGeneration,
                event.eventKey,
                event.aggregateRef,
                event.edges.map {
                    GraphEdgeObservation(it.from, it.to, it.relation, event.source, event.occurredAt, event.version)
                },
                clock.instant(),
            )
        }.flatMap {
            mutation(
                operation,
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
                val nodes = event.nodes.fold(Uni.createFrom().voidItem()) { chain, node ->
                    chain.flatMap { upsertNode(operation, node).replaceWithVoid() }
                }
                event.edges.fold(nodes) { chain, edge ->
                    chain.flatMap { insertEdge(operation, event, edge).replaceWithVoid() }
                }
            }
        }

    private fun upsertNode(operation: ContextSqlOperation, node: RailNode): Uni<Int> = mutation(
        operation,
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system,
             source_ref, display_label, classification, valid_from, valid_to, recorded_at, source_version)
            VALUES (:rowId, :key, :bankScope, :generation, 'COMPLAINT', :type, :source,
                    :sourceRef, :label, 'RESTRICTED', :validFrom, NULL, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, node_key) DO UPDATE SET
              node_type = EXCLUDED.node_type, source_ref = EXCLUDED.source_ref,
              display_label = EXCLUDED.display_label, valid_from = EXCLUDED.valid_from,
              recorded_at = EXCLUDED.recorded_at, source_version = EXCLUDED.source_version
            WHERE context_nodes.source_system = EXCLUDED.source_system
              AND context_nodes.source_version < EXCLUDED.source_version
        """.trimIndent(),
        node.values(bankScope, projectionGeneration, clock.instant()),
    )

    private fun insertEdge(operation: ContextSqlOperation, event: RailProjectionEvent, edge: RailEdge): Uni<Int> =
        mutation(
            operation,
            """INSERT INTO context_edges
            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type,
             source_system, evidence_ref, valid_from, valid_to, recorded_at, source_version, retained_history_from)
            VALUES (:id, :bankScope, :generation, 'COMPLAINT', :fromKey, :toKey, :relation,
                    :source, :evidenceRef, :validFrom, NULL, :recordedAt, :version, :validFrom)
            ON CONFLICT (bank_scope, projection_generation, edge_id) DO UPDATE SET
              retained_history_from = LEAST(context_edges.retained_history_from, EXCLUDED.retained_history_from)
            """.trimIndent(),
            mapOf(
                "id" to railStableId("COMPLAINT|${edge.from}|${edge.to}|${edge.relation}"),
                "bankScope" to bankScope,
                "generation" to projectionGeneration,
                "fromKey" to edge.from,
                "toKey" to edge.to,
                "relation" to edge.relation,
                "source" to event.source,
                "evidenceRef" to event.eventKey,
                "validFrom" to event.occurredAt,
                "recordedAt" to clock.instant(),
                "version" to event.version,
            ),
        )

    private fun mutation(operation: ContextSqlOperation, sql: String, values: Map<String, Any>): Uni<Int> =
        operation.sql { session ->
            val query = session.createNativeMutationQuery(sql)
            values.forEach { (name, value) -> query.setParameter(name, value) }
            query.executeUpdate()
        }

    private fun lagGauge(stream: String) = AtomicLong().also {
        meters.gauge(METRIC_LAG, io.micrometer.core.instrument.Tags.of("stream", stream), it)
    }

    private companion object {
        const val ITEM_CLEARED = "openbank.clearing.item.cleared"
        const val SEPA_RETURNED = "sepa.payment.returned"
        const val CLEARING_SOURCE = "clearing-service"
        const val SEPA_SOURCE = "sepa-payment"
        const val CLEARING_STREAM = "clearing"
        const val SEPA_RETURN_STREAM = "sepa-return"
        const val MAX_EVENT_BYTES = 64 * 1024
        const val METRIC_EVENTS = "openbank_context_projection_events_total"
        const val METRIC_LAG = "openbank_context_projection_lag_seconds"
        const val METRIC_DURATION = "openbank_context_projection_duration"
        val RETURN_REASON = Regex("[A-Z0-9]{1,8}")
    }
}

private fun JsonNode.railVersion(requireStrict: Boolean): Long {
    val field = path("version")
    require(!requireStrict || field.isIntegralNumber) { "event has no strict aggregate version" }
    return field.takeIf { it.isIntegralNumber }?.asLong()?.also {
        require(it >= 0) { "event version must be non-negative" }
    } ?: Instant.parse(railText("occurredAt")).toEpochMilli()
}

private fun JsonNode.railText(name: String): String = path(name).takeIf { it.isTextual }?.asText()?.trim().orEmpty()
private fun railUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
private fun railStableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

private data class RailNode(
    val key: String,
    val type: String,
    val source: String,
    val sourceRef: String,
    val label: String,
    val validFrom: Instant,
    val version: Long,
) {
    fun values(bankScope: String, generation: Long, recordedAt: Instant): Map<String, Any> = mapOf(
        "rowId" to railStableId("$bankScope|$generation|$key"),
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

private fun RailNode.observation() = GraphNodeObservation(
    key,
    type,
    source,
    sourceRef,
    label,
    validFrom,
    version,
    type in setOf("CLEARING_ITEM", "CLEARING_EVIDENCE", "RETURN_EVIDENCE"),
)

private data class RailEdge(val from: String, val to: String, val relation: String)

private data class RailProjectionEvent(
    val eventKey: String,
    val aggregateRef: String,
    val source: String,
    val version: Long,
    val occurredAt: Instant,
    val nodes: List<RailNode>,
    val edges: List<RailEdge>,
) {
    companion object {
        fun clearing(
            paymentId: String,
            itemId: String,
            batchId: String,
            version: Long,
            at: Instant,
        ): RailProjectionEvent {
            val payment = "transaction:$paymentId"
            val item = "clearing-item:$itemId"
            val evidence = "clearing-evidence:$itemId:$version"
            return RailProjectionEvent(
                "clearing:$itemId:$version",
                item,
                "clearing-service",
                version,
                at,
                listOf(
                    RailNode(payment, "PAYMENT", "clearing-service", paymentId, "Payment", at, version),
                    RailNode(item, "CLEARING_ITEM", "clearing-service", itemId, "Clearing item", at, version),
                    RailNode(
                        evidence,
                        "CLEARING_EVIDENCE",
                        "clearing-service",
                        batchId,
                        "Clearing acknowledged · SETTLED",
                        at,
                        version,
                    ),
                ),
                listOf(RailEdge(payment, item, "SUBMITTED_TO"), RailEdge(item, evidence, "SETTLED")),
            )
        }

        fun sepaReturn(
            paymentId: String,
            reason: String?,
            reversalPerformed: Boolean,
            reversalTransactionId: String?,
            version: Long,
            at: Instant,
        ): RailProjectionEvent {
            val payment = "transaction:$paymentId"
            val evidence = "return-evidence:sepa:$paymentId:$version"
            val reversal = reversalTransactionId?.let { "reversal-transaction:$it" }
            val label = buildString {
                append("SEPA return")
                reason?.let { append(" · ").append(it) }
                append(if (reversalPerformed) " · reversal confirmed" else " · reversal unavailable")
            }
            return RailProjectionEvent(
                "sepa-return:$paymentId:$version",
                evidence,
                "sepa-payment",
                version,
                at,
                listOfNotNull(
                    RailNode(payment, "PAYMENT", "sepa-payment", paymentId, "Payment", at, version),
                    RailNode(evidence, "RETURN_EVIDENCE", "sepa-payment", paymentId, label, at, version),
                    reversalTransactionId?.let { id ->
                        RailNode(
                            "reversal-transaction:$id",
                            "REVERSAL_TRANSACTION",
                            "sepa-payment",
                            id,
                            "Reversal transaction reference",
                            at,
                            version,
                        )
                    },
                ),
                listOfNotNull(
                    RailEdge(payment, evidence, "RETURNED_BY"),
                    reversal?.let { RailEdge(payment, it, "REVERSED_BY") },
                ),
            )
        }
    }
}
