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
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@ApplicationScoped
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class ComplaintProjectionConsumer(
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
        meters.gauge(METRIC_LAG, Tags.of("stream", "complaint"), it)
    }

    @Incoming("dispute-events-in")
    suspend fun consume(payload: String) {
        val started = System.nanoTime()
        try {
            require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) {
                "dispute event exceeds $MAX_EVENT_BYTES bytes"
            }
            val root = objectMapper.readTree(payload)
            if (!root.text("eventType").startsWith("complaint.")) {
                meters.counter(METRIC_EVENTS, "stream", "complaint", "outcome", "ignored").increment()
                return
            }
            require(root.text("sourceService") == SOURCE_SERVICE) { "unexpected complaint event source" }
            val event = parse(root)
            sessions.withTransaction { session, _ ->
                session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                    .setParameter("bank", bankScope).singleResult.flatMap {
                        session.createNativeQuery(
                            "select set_config('statement_timeout', :timeout, true)",
                            String::class.java,
                        )
                            .setParameter("timeout", "${timeoutMs}ms").singleResult
                    }.flatMap { project(session, event) }
            }
                .ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
            meters.counter(METRIC_EVENTS, "stream", "complaint", "outcome", "projected").increment()
            projectionLagSeconds.set((clock.instant().epochSecond - event.occurredAt.epochSecond).coerceAtLeast(0))
        } catch (failure: RuntimeException) {
            meters.counter(METRIC_EVENTS, "stream", "complaint", "outcome", "failed").increment()
            throw failure
        } finally {
            meters.timer(METRIC_DURATION, "stream", "complaint")
                .record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun project(session: Mutiny.Session, event: ComplaintProjectionEvent): Uni<Void> =
        appendRevision(session, event).flatMap { projectCurrent(session, event) }

    /** Every delivered revision is retained even when it is older than the current projection. */
    private fun appendRevision(session: Mutiny.Session, event: ComplaintProjectionEvent): Uni<Void> = mutation(
        session,
        """INSERT INTO context_complaint_revisions
            (bank_scope, projection_generation, complaint_id, source_version, reference, event_key,
             event_type, status, account_id, transaction_id, dispute_id, occurred_at, content_hash)
            VALUES (:bank, :generation, :id, :version, :reference, :eventKey, :type, :status,
                    :accountId, :transactionId, :disputeId, :occurredAt, :hash)
            ON CONFLICT (bank_scope, projection_generation, complaint_id, source_version) DO NOTHING
        """.trimIndent(),
        mapOf(
            "bank" to bankScope,
            "generation" to projectionGeneration,
            "id" to UUID.fromString(event.complaintId),
            "version" to event.sourceVersion,
            "reference" to event.reference,
            "eventKey" to event.eventKey,
            "type" to event.eventType,
            "status" to event.status,
            "accountId" to event.accountId?.let(UUID::fromString),
            "transactionId" to event.transactionId?.let(UUID::fromString),
            "disputeId" to event.disputeId?.let(UUID::fromString),
            "occurredAt" to event.occurredAt,
            "hash" to event.contentHash,
        ),
    ).flatMap {
        session.createNativeQuery(
            """SELECT content_hash FROM context_complaint_revisions
               WHERE bank_scope = :bank AND projection_generation = :generation
                 AND complaint_id = :id AND source_version = :version
            """.trimIndent(),
            String::class.java,
        ).setParameter("bank", bankScope).setParameter("generation", projectionGeneration)
            .setParameter("id", UUID.fromString(event.complaintId)).setParameter("version", event.sourceVersion)
            .singleResult.invoke { stored -> check(stored == event.contentHash) { "conflicting complaint revision" } }
            .replaceWithVoid()
    }

    private fun projectCurrent(session: Mutiny.Session, event: ComplaintProjectionEvent): Uni<Void> = mutation(
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
            "aggregateRef" to event.complaintKey,
            "version" to event.sourceVersion,
            "occurredAt" to event.occurredAt,
            "processedAt" to clock.instant(),
        ),
    ).flatMap { inserted ->
        if (inserted == 0) {
            Uni.createFrom().voidItem()
        } else {
            upsertNode(session, event.complaintNode).flatMap { changed ->
                if (changed == 0) {
                    Uni.createFrom().voidItem()
                } else {
                    upsertOptionalNode(session, event.accountNode)
                        .flatMap { upsertOptionalNode(session, event.transactionNode) }
                        .flatMap { upsertOptionalNode(session, event.disputeNode) }
                        .flatMap { deletePriorEdges(session, event) }
                        .flatMap { upsertEdges(session, event) }
                }
            }
        }
    }

    private fun deletePriorEdges(session: Mutiny.Session, event: ComplaintProjectionEvent): Uni<Int> = mutation(
        session,
        """DELETE FROM context_edges
             WHERE bank_scope = :bankScope AND projection_generation = :generation
               AND namespace = 'COMPLAINT' AND from_key = :root
        """.trimIndent(),
        mapOf("bankScope" to bankScope, "generation" to projectionGeneration, "root" to event.complaintKey),
    )

    private fun upsertOptionalNode(session: Mutiny.Session, node: ProjectionNode?): Uni<Int> =
        node?.let { upsertNode(session, it) } ?: Uni.createFrom().item(0)

    private fun upsertNode(session: Mutiny.Session, node: ProjectionNode): Uni<Int> = mutation(
        session,
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system, source_ref, display_label,
             classification, valid_from, valid_to, recorded_at, source_version)
            VALUES (:rowId, :key, :bankScope, :generation, 'COMPLAINT', :type, :source, :sourceRef, :label,
                    :classification, :validFrom, NULL, :recordedAt, :version)
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
            WHERE context_nodes.source_version < EXCLUDED.source_version
              AND NOT (EXCLUDED.node_type = 'TRANSACTION' AND context_nodes.source_system = 'domestic-payment')
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
            "classification" to node.classification,
            "validFrom" to node.validFrom,
            "recordedAt" to clock.instant(),
            "version" to node.sourceVersion,
        ),
    )

    private fun upsertEdges(session: Mutiny.Session, event: ComplaintProjectionEvent): Uni<Void> {
        var chain: Uni<*> = Uni.createFrom().voidItem()
        event.edges.forEach { edge -> chain = chain.flatMap { upsertEdge(session, edge) } }
        return chain.replaceWithVoid()
    }

    private fun upsertEdge(session: Mutiny.Session, edge: ProjectionEdge): Uni<Int> = mutation(
        session,
        """INSERT INTO context_edges
            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type, source_system, evidence_ref,
             valid_from, valid_to, recorded_at, source_version)
            VALUES (:id, :bankScope, :generation, 'COMPLAINT', :fromKey, :toKey, :relation,
                    :source, :evidenceRef, :validFrom, NULL, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, edge_id) DO UPDATE SET
              evidence_ref = EXCLUDED.evidence_ref,
              valid_from = EXCLUDED.valid_from,
              valid_to = NULL,
              recorded_at = EXCLUDED.recorded_at,
              source_version = EXCLUDED.source_version
            WHERE context_edges.source_version < EXCLUDED.source_version
        """.trimIndent(),
        mapOf(
            "id" to stableId("$bankScope|$projectionGeneration|${edge.id}"),
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

    private fun mutation(session: Mutiny.Session, sql: String, values: Map<String, Any?>): Uni<Int> {
        val query = session.createNativeMutationQuery(sql)
        values.forEach { (name, value) -> query.setParameter(name, value) }
        return query.executeUpdate()
    }

    private fun parse(root: JsonNode): ComplaintProjectionEvent {
        require(root.long("schemaVersion") == SCHEMA_VERSION) { "unsupported complaint schemaVersion" }
        val id = root.text("complaintId")
        val reference = root.text("reference")
        val status = root.text("status")
        val eventType = root.text("eventType")
        val compatibilityVersion = root.long("sourceVersion")
        val aggregateRevision = root.long("aggregateRevision")
        require(aggregateRevision > 0 || !requireStrictRevisions) {
            "complaint event has no strict aggregateRevision"
        }
        if (aggregateRevision <= 0) {
            meters.counter(METRIC_EVENTS, "stream", "complaint", "outcome", "legacy_revision").increment()
        }
        val version = aggregateRevision.takeIf { it > 0 } ?: compatibilityVersion
        val occurredAt = Instant.parse(root.text("occurredAt"))
        require(
            version > 0 &&
                runCatching { UUID.fromString(id) }.isSuccess &&
                reference.isNotBlank() &&
                reference.length <= MAX_REFERENCE_LENGTH &&
                status.isNotBlank() &&
                eventType in EVENT_TYPES,
        ) {
            "complaint event misses required identity/version fields"
        }
        return ComplaintProjectionEvent(
            complaintId = id,
            reference = reference,
            eventType = eventType,
            status = status,
            sourceVersion = version,
            occurredAt = occurredAt,
            accountId = root.optionalUuid("accountId"),
            transactionId = root.optionalUuid("transactionId"),
            disputeId = root.optionalUuid("disputeId"),
        )
    }

    private fun JsonNode.text(name: String): String = path(name).takeIf { it.isTextual }?.asText()?.trim().orEmpty()
    private fun JsonNode.long(name: String): Long = path(name).takeIf { it.canConvertToLong() }?.asLong() ?: 0
    private fun JsonNode.optionalText(name: String): String? = text(name).takeIf(String::isNotBlank)
    private fun JsonNode.optionalUuid(name: String): String? = optionalText(name)?.also { UUID.fromString(it) }

    private fun stableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_EVENT_BYTES = 64 * 1024
        const val MAX_REFERENCE_LENGTH = 200
        const val METRIC_EVENTS = "openbank_context_projection_events_total"
        const val METRIC_LAG = "openbank_context_projection_lag_seconds"
        const val METRIC_DURATION = "openbank_context_projection_duration"
        const val SOURCE_SERVICE = "dispute-service"
        val EVENT_TYPES = setOf(
            "complaint.received",
            "complaint.interim_reply",
            "complaint.resolved",
            "complaint.closed",
        )
    }
}

private data class ProjectionNode(
    val key: String,
    val type: String,
    val sourceRef: String,
    val label: String,
    val classification: String,
    val validFrom: Instant,
    val sourceVersion: Long,
)

private data class ProjectionEdge(
    val id: UUID,
    val from: String,
    val to: String,
    val relation: String,
    val evidenceRef: String,
    val validFrom: Instant,
    val sourceVersion: Long,
)

private data class ComplaintProjectionEvent(
    val complaintId: String,
    val reference: String,
    val eventType: String,
    val status: String,
    val sourceVersion: Long,
    val occurredAt: Instant,
    val accountId: String?,
    val transactionId: String?,
    val disputeId: String?,
) {
    val contentHash: String = listOf(
        complaintId, reference, eventType, status, sourceVersion.toString(), occurredAt.toString(),
        accountId.orEmpty(), transactionId.orEmpty(), disputeId.orEmpty(),
    ).joinToString("") { "${it.length}:$it" }.toByteArray(StandardCharsets.UTF_8).let { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    val complaintKey = "complaint:$reference"
    val eventKey = "complaint:$complaintId:$sourceVersion"
    val complaintNode = node(complaintKey, "COMPLAINT", complaintId, "Complaint $reference · $status", "RESTRICTED")
    val accountNode = accountId?.let { node("account:$it", "ACCOUNT", it, "Account reference", "RESTRICTED") }
    val transactionNode = transactionId?.let {
        node("transaction:$it", "TRANSACTION", it, "Transaction reference", "RESTRICTED")
    }
    val disputeNode = disputeId?.let { node("dispute:$it", "DISPUTE", it, "Dispute reference", "RESTRICTED") }
    val edges = listOfNotNull(
        accountNode?.let { edge(complaintKey, it.key, "CONCERNS_ACCOUNT") },
        transactionNode?.let { edge(complaintKey, it.key, "CONCERNS_TRANSACTION") },
        disputeNode?.let { edge(complaintKey, it.key, "RELATED_DISPUTE") },
    )

    private fun node(key: String, type: String, sourceRef: String, label: String, classification: String) =
        ProjectionNode(key, type, sourceRef, label, classification, occurredAt, sourceVersion)

    private fun edge(from: String, to: String, relation: String) = ProjectionEdge(
        UUID.nameUUIDFromBytes("COMPLAINT|$from|$to|$relation".toByteArray(StandardCharsets.UTF_8)),
        from,
        to,
        relation,
        eventKey,
        occurredAt,
        sourceVersion,
    )
}
