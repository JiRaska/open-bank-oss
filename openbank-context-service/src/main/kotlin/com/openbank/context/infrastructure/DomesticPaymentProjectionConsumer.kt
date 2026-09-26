// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
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

/** Projects only explicit domestic-payment lifecycle facts; no amount/time correlation is allowed. */
@ApplicationScoped
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class DomesticPaymentProjectionConsumer(
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
    private val projectionLagSeconds = AtomicLong().also {
        meters.gauge(METRIC_LAG, Tags.of("stream", STREAM), it)
    }

    @Incoming("domestic-payment-events-in")
    suspend fun consume(payload: String) {
        val started = System.nanoTime()
        try {
            require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) {
                "domestic payment event exceeds $MAX_EVENT_BYTES bytes"
            }
            val root = objectMapper.readTree(payload)
            if (root.text("eventType") !in EVENT_TYPES) {
                meters.counter(METRIC_EVENTS, "stream", STREAM, "outcome", "ignored").increment()
                return
            }
            require(root.text("sourceService") == SOURCE_SERVICE) { "unexpected domestic payment event source" }
            val event = parse(root)
            sessions.withTransaction { session, _ ->
                session.createNativeQuery("select set_config('statement_timeout', :timeout, true)", String::class.java)
                    .setParameter("timeout", "${timeoutMs}ms").singleResult
                    .flatMap { project(session, event) }
            }
                .ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
            meters.counter(METRIC_EVENTS, "stream", STREAM, "outcome", "projected").increment()
            projectionLagSeconds.set((clock.instant().epochSecond - event.occurredAt.epochSecond).coerceAtLeast(0))
        } catch (failure: RuntimeException) {
            meters.counter(METRIC_EVENTS, "stream", STREAM, "outcome", "failed").increment()
            throw failure
        } finally {
            meters.timer(METRIC_DURATION, "stream", STREAM)
                .record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun project(session: Mutiny.Session, event: DomesticPaymentProjectionEvent): Uni<Void> =
        GraphNodeHistoryWriter.append(
            session,
            bankScope,
            projectionGeneration,
            event.eventKey,
            event.paymentKey,
            listOf(event.paymentNode.observation(), event.stageNode.observation()),
            clock.instant(),
        ).flatMap {
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
                    "source" to SOURCE_SERVICE,
                    "aggregateRef" to event.paymentKey,
                    "version" to event.sourceVersion,
                    "occurredAt" to event.occurredAt,
                    "processedAt" to clock.instant(),
                ),
            )
        }.flatMap { inserted ->
            if (inserted == 0) {
                Uni.createFrom().voidItem()
            } else {
                upsertNode(session, event.paymentNode)
                    .flatMap { upsertNode(session, event.stageNode) }
                    .flatMap { upsertEdge(session, event.stageEdge) }
                    .replaceWithVoid()
            }
        }

    private fun upsertNode(session: Mutiny.Session, node: PaymentProjectionNode): Uni<Int> = mutation(
        session,
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system,
             source_ref, display_label, classification, valid_from, valid_to, recorded_at, source_version)
            VALUES (:rowId, :key, :bankScope, :generation, 'COMPLAINT', :type, :source,
                    :sourceRef, :label, 'RESTRICTED', :validFrom, NULL, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, node_key) DO UPDATE SET
              node_type = EXCLUDED.node_type,
              source_system = EXCLUDED.source_system,
              source_ref = EXCLUDED.source_ref,
              display_label = EXCLUDED.display_label,
              classification = EXCLUDED.classification,
              valid_from = EXCLUDED.valid_from,
              valid_to = NULL,
              recorded_at = EXCLUDED.recorded_at,
              source_version = EXCLUDED.source_version
            WHERE context_nodes.source_system <> EXCLUDED.source_system
               OR context_nodes.source_version < EXCLUDED.source_version
        """.trimIndent(),
        mapOf(
            "rowId" to stableId("$bankScope|$projectionGeneration|${node.key}"),
            "key" to node.key,
            "bankScope" to bankScope,
            "generation" to projectionGeneration,
            "type" to node.type,
            "source" to SOURCE_SERVICE,
            "sourceRef" to node.sourceRef,
            "label" to node.label,
            "validFrom" to node.validFrom,
            "recordedAt" to clock.instant(),
            "version" to node.sourceVersion,
        ),
    )

    private fun upsertEdge(session: Mutiny.Session, edge: PaymentProjectionEdge): Uni<Int> = mutation(
        session,
        """INSERT INTO context_edges
            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type,
             source_system, evidence_ref, valid_from, valid_to, recorded_at, source_version)
            VALUES (:id, :bankScope, :generation, 'COMPLAINT', :fromKey, :toKey, :relation,
                    :source, :evidenceRef, :validFrom, NULL, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, edge_id) DO NOTHING
        """.trimIndent(),
        mapOf(
            "id" to edge.id,
            "bankScope" to bankScope,
            "generation" to projectionGeneration,
            "fromKey" to edge.from,
            "toKey" to edge.to,
            "relation" to edge.relation,
            "source" to SOURCE_SERVICE,
            "evidenceRef" to edge.evidenceRef,
            "validFrom" to edge.validFrom,
            "recordedAt" to clock.instant(),
            "version" to edge.sourceVersion,
        ),
    )

    private fun mutation(session: Mutiny.Session, sql: String, values: Map<String, Any>): Uni<Int> {
        val query = session.createNativeMutationQuery(sql)
        values.forEach { (name, value) -> query.setParameter(name, value) }
        return query.executeUpdate()
    }

    private fun parse(root: JsonNode): DomesticPaymentProjectionEvent {
        val paymentId = root.text("paymentId")
        val eventType = root.text("eventType")
        val aggregateRevision = root.long("aggregateRevision")
        require(aggregateRevision > 0 || !requireStrictRevisions) {
            "domestic payment event has no strict aggregateRevision"
        }
        if (aggregateRevision <= 0) {
            meters.counter(METRIC_EVENTS, "stream", STREAM, "outcome", "legacy_revision").increment()
        }
        val occurredAt = Instant.parse(root.text("occurredAt"))
        val compatibilityVersion = occurredAt.epochSecond * NANOS_PER_SECOND + occurredAt.nano
        val version = aggregateRevision.takeIf { it > 0 } ?: compatibilityVersion
        val status = if (eventType == CREATED) root.text("status") else root.text("newStatus")
        require(
            runCatching { UUID.fromString(paymentId) }.isSuccess &&
                version > 0 &&
                status in PAYMENT_STATUSES,
        ) { "domestic payment event misses required identity/revision/status fields" }
        return DomesticPaymentProjectionEvent(paymentId, eventType, status, version, occurredAt)
    }

    private fun JsonNode.text(name: String): String = path(name).takeIf { it.isTextual }?.asText()?.trim().orEmpty()
    private fun JsonNode.long(name: String): Long = path(name).takeIf { it.canConvertToLong() }?.asLong() ?: 0
    private fun stableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CREATED = "DOMESTIC_PAYMENT_CREATED"
        const val STATUS_CHANGED = "DOMESTIC_PAYMENT_STATUS_CHANGED"
        const val SOURCE_SERVICE = "domestic-payment"
        const val STREAM = "domestic_payment"
        const val MAX_EVENT_BYTES = 64 * 1024
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val METRIC_EVENTS = "openbank_context_projection_events_total"
        const val METRIC_LAG = "openbank_context_projection_lag_seconds"
        const val METRIC_DURATION = "openbank_context_projection_duration"
        val EVENT_TYPES = setOf(CREATED, STATUS_CHANGED)
        val PAYMENT_STATUSES = setOf(
            "RECEIVED",
            "VALIDATED",
            "SENT_TO_CLEARING",
            "SETTLED",
            "REJECTED",
            "RETURNED",
            "CANCELLED",
        )
    }
}

private data class PaymentProjectionNode(
    val key: String,
    val type: String,
    val sourceRef: String,
    val label: String,
    val validFrom: Instant,
    val sourceVersion: Long,
)

private fun PaymentProjectionNode.observation() = GraphNodeObservation(
    key,
    type,
    "domestic-payment",
    sourceRef,
    label,
    validFrom,
    sourceVersion,
    true,
)

private data class PaymentProjectionEdge(
    val id: UUID,
    val from: String,
    val to: String,
    val relation: String,
    val evidenceRef: String,
    val validFrom: Instant,
    val sourceVersion: Long,
)

private data class DomesticPaymentProjectionEvent(
    val paymentId: String,
    val eventType: String,
    val status: String,
    val sourceVersion: Long,
    val occurredAt: Instant,
) {
    val paymentKey = "transaction:$paymentId"
    val eventKey = "domestic-payment:$paymentId:$sourceVersion"
    val stageKey = "payment-stage:domestic:$paymentId:$sourceVersion"
    val paymentNode = PaymentProjectionNode(
        paymentKey,
        "PAYMENT",
        paymentId,
        "Domestic payment · $status",
        occurredAt,
        sourceVersion,
    )
    val stageNode = PaymentProjectionNode(
        stageKey,
        if (status in RAIL_STATUSES) "RAIL_EVIDENCE" else "PAYMENT_STAGE",
        "$paymentId:$sourceVersion",
        "Domestic payment · $status",
        occurredAt,
        sourceVersion,
    )
    val stageEdge = PaymentProjectionEdge(
        UUID.nameUUIDFromBytes("COMPLAINT|$paymentKey|$stageKey|${relation()}".toByteArray(StandardCharsets.UTF_8)),
        paymentKey,
        stageKey,
        relation(),
        eventKey,
        occurredAt,
        sourceVersion,
    )

    private fun relation(): String = when {
        eventType == "DOMESTIC_PAYMENT_CREATED" -> "CREATED"
        status == "SENT_TO_CLEARING" -> "SUBMITTED_TO"
        status == "RETURNED" -> "RETURNED_BY"
        else -> status
    }

    private companion object {
        val RAIL_STATUSES = setOf("SENT_TO_CLEARING", "SETTLED", "RETURNED")
    }
}
