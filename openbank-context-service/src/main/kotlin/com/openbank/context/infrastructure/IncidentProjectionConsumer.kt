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

/** Privacy-minimised projection of the durable DORA incident register. */
@ApplicationScoped
@Suppress("TooGenericExceptionCaught")
class IncidentProjectionConsumer(
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
    private val projectionLagSeconds = AtomicLong().also { meters.gauge(METRIC_LAG, Tags.of("stream", "incident"), it) }

    @Incoming("ict-incident-events-in")
    suspend fun consume(payload: String) {
        val started = System.nanoTime()
        try {
            require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) {
                "ICT incident event exceeds $MAX_EVENT_BYTES bytes"
            }
            val root = objectMapper.readTree(payload)
            require(root.text("sourceService") == SOURCE_SERVICE) { "unexpected ICT incident event source" }
            require(root.long("schemaVersion") == SCHEMA_VERSION) { "unsupported ICT incident schemaVersion" }
            val event = parse(root, incidentDigest(payload))
            sessions.withTransaction { session, _ -> project(session, event) }
                .ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
            meters.counter(METRIC_EVENTS, "stream", "incident", "outcome", "projected").increment()
            projectionLagSeconds.set((clock.instant().epochSecond - event.occurredAt.epochSecond).coerceAtLeast(0))
        } catch (failure: RuntimeException) {
            meters.counter(METRIC_EVENTS, "stream", "incident", "outcome", "failed").increment()
            throw failure
        } finally {
            meters.timer(METRIC_DURATION, "stream", "incident")
                .record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun project(session: Mutiny.Session, event: IncidentProjectionEvent): Uni<Void> = session.createNativeQuery(
        "SELECT COALESCE(content_digest, '') FROM context_projection_events " +
            "WHERE bank_scope = :bank AND projection_generation IS NULL " +
            "AND source_system = :source AND aggregate_ref = :aggregate AND source_version = :version",
        String::class.java,
    ).setParameter("bank", bankScope).setParameter("source", SOURCE_SERVICE)
        .setParameter("aggregate", event.rootKey).setParameter("version", event.sourceVersion)
        .resultList.invoke { digests ->
            digests.forEach { digest ->
                if (digest.isEmpty()) {
                    meters.counter(METRIC_EVENTS, "stream", "incident", "outcome", "legacy_digest_unavailable")
                        .increment()
                } else {
                    require(digest == event.contentDigest) { "conflicting legacy ICT incident revision replay" }
                }
            }
        }.flatMap { projectCurrent(session, event) }

    private fun projectCurrent(session: Mutiny.Session, event: IncidentProjectionEvent): Uni<Void> = mutation(
        session,
        """INSERT INTO context_projection_events
                (bank_scope, projection_generation, event_key, source_system, aggregate_ref, source_version, occurred_at, processed_at,
                 content_digest)
                VALUES (:bankScope, :generation, :eventKey, :source, :aggregateRef, :version, :occurredAt, :processedAt,
                        :digest)
                ON CONFLICT (bank_scope, projection_generation, event_key) DO NOTHING
        """.trimIndent(),
        mapOf(
            "bankScope" to bankScope,
            "generation" to projectionGeneration,
            "eventKey" to event.eventKey,
            "source" to SOURCE_SERVICE,
            "aggregateRef" to event.rootKey,
            "version" to event.sourceVersion,
            "occurredAt" to event.occurredAt,
            "processedAt" to clock.instant(),
            "digest" to event.contentDigest,
        ),
    ).flatMap { inserted ->
        if (inserted == 0) {
            verifyReplay(session, event)
        } else {
            upsertIncident(session, event).flatMap { changed ->
                if (changed == 0) Uni.createFrom().voidItem() else replaceAffectedServices(session, event)
            }
        }
    }

    private fun verifyReplay(session: Mutiny.Session, event: IncidentProjectionEvent): Uni<Void> =
        session.createNativeQuery(
            "SELECT COALESCE(content_digest, '') FROM context_projection_events " +
                "WHERE bank_scope = :bankScope AND projection_generation = :generation " +
                "AND event_key = :eventKey",
            String::class.java,
        ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
            .setParameter("eventKey", event.eventKey).singleResult.flatMap { stored ->
                if (stored.isEmpty()) {
                    meters.counter(
                        METRIC_EVENTS,
                        "stream",
                        "incident",
                        "outcome",
                        "legacy_digest_unavailable",
                    ).increment()
                } else {
                    require(stored == event.contentDigest) { "conflicting ICT incident revision replay" }
                }
                Uni.createFrom().voidItem()
            }

    private fun upsertIncident(session: Mutiny.Session, event: IncidentProjectionEvent): Uni<Int> = upsertNode(
        session,
        key = event.rootKey,
        type = "INCIDENT",
        sourceRef = event.id,
        label = "${event.severity} · ${event.status}",
        validFrom = event.detectedAt,
        sourceVersion = event.sourceVersion,
    )

    private fun replaceAffectedServices(session: Mutiny.Session, event: IncidentProjectionEvent): Uni<Void> = mutation(
        session,
        """DELETE FROM context_edges
             WHERE bank_scope = :bankScope AND projection_generation = :generation
               AND namespace = 'INCIDENT' AND from_key = :root AND relation_type = 'AFFECTS_SERVICE'
        """.trimIndent(),
        mapOf("bankScope" to bankScope, "generation" to projectionGeneration, "root" to event.rootKey),
    ).flatMap {
        var chain: Uni<*> = Uni.createFrom().voidItem()
        event.affectedServices.distinct().sorted().forEach { service ->
            val serviceKey = "service:$service"
            chain = chain.flatMap {
                upsertNode(session, serviceKey, "SERVICE", service, service, event.detectedAt, event.sourceVersion)
            }.flatMap {
                mutation(
                    session,
                    """INSERT INTO context_edges
                        (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type,
                         source_system, evidence_ref, valid_from, recorded_at, source_version)
                        VALUES (:id, :bankScope, :generation, 'INCIDENT', :root, :serviceKey, 'AFFECTS_SERVICE',
                                :source, :evidence, :validFrom, :recordedAt, :version)
                        ON CONFLICT (bank_scope, projection_generation, edge_id) DO UPDATE SET recorded_at = EXCLUDED.recorded_at,
                          source_version = EXCLUDED.source_version
                        WHERE context_edges.source_version < EXCLUDED.source_version
                    """.trimIndent(),
                    mapOf(
                        "id" to incidentStableId("$bankScope|$projectionGeneration|${event.rootKey}|$serviceKey"),
                        "bankScope" to bankScope,
                        "generation" to projectionGeneration,
                        "root" to event.rootKey,
                        "serviceKey" to serviceKey,
                        "source" to SOURCE_SERVICE,
                        "evidence" to event.eventKey,
                        "validFrom" to event.detectedAt,
                        "recordedAt" to clock.instant(),
                        "version" to event.sourceVersion,
                    ),
                )
            }
        }
        chain.replaceWithVoid()
    }

    private fun upsertNode(
        session: Mutiny.Session,
        key: String,
        type: String,
        sourceRef: String,
        label: String,
        validFrom: Instant,
        sourceVersion: Long,
    ): Uni<Int> = mutation(
        session,
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system,
             source_ref, display_label, classification, valid_from, recorded_at, source_version)
            VALUES (:id, :key, :bankScope, :generation, 'INCIDENT', :type, :source, :sourceRef, :label,
                    'INTERNAL', :validFrom, :recordedAt, :version)
            ON CONFLICT (bank_scope, projection_generation, node_key) DO UPDATE SET
              display_label = EXCLUDED.display_label, valid_from = EXCLUDED.valid_from,
              recorded_at = EXCLUDED.recorded_at, source_version = EXCLUDED.source_version
            WHERE context_nodes.source_version < EXCLUDED.source_version
        """.trimIndent(),
        mapOf(
            "id" to incidentStableId("$bankScope|$projectionGeneration|$key"),
            "key" to key,
            "bankScope" to bankScope,
            "generation" to projectionGeneration,
            "type" to type,
            "source" to SOURCE_SERVICE,
            "sourceRef" to sourceRef,
            "label" to label,
            "validFrom" to validFrom,
            "recordedAt" to clock.instant(),
            "version" to sourceVersion,
        ),
    )

    private fun mutation(session: Mutiny.Session, sql: String, values: Map<String, Any>): Uni<Int> {
        val query = session.createNativeMutationQuery(sql)
        values.forEach { (name, value) -> query.setParameter(name, value) }
        return query.executeUpdate()
    }

    private fun parse(root: JsonNode, contentDigest: String): IncidentProjectionEvent {
        val incident = root.path("incident")
        val id = incident.text("id")
        val compatibilityVersion = root.long("sourceVersion")
        val aggregateRevision = root.long("aggregateRevision")
        require(aggregateRevision > 0 || !requireStrictRevisions) {
            "ICT incident event has no strict aggregateRevision"
        }
        if (aggregateRevision <= 0) {
            meters.counter(METRIC_EVENTS, "stream", "incident", "outcome", "legacy_revision").increment()
        }
        val sourceVersion = aggregateRevision.takeIf { it > 0 } ?: compatibilityVersion
        val eventType = root.text("eventType")
        val severity = incident.text("severity")
        val status = incident.text("status")
        val occurredAt = Instant.parse(root.text("occurredAt"))
        val detectedAt = Instant.parse(incident.text("detectedAt"))
        val servicesNode = incident.path("affectedServices")
        val services = servicesNode.takeIf { it.isArray }?.map { it.asText().trim() }.orEmpty()
        require(
            runCatching { UUID.fromString(id) }.isSuccess &&
                compatibilityVersion > 0 &&
                sourceVersion > 0 &&
                eventType in EVENT_TYPES &&
                severity.isNotBlank() &&
                status.isNotBlank() &&
                servicesNode.isArray &&
                services.size <= MAX_AFFECTED_SERVICES &&
                services.all { it.isNotBlank() && it.length <= MAX_SERVICE_LENGTH },
        ) {
            "ICT incident event misses required identity/version fields or exceeds service limit"
        }
        return IncidentProjectionEvent(
            id,
            severity,
            status,
            services,
            detectedAt,
            occurredAt,
            sourceVersion,
            eventType,
            contentDigest,
        )
    }

    private companion object {
        const val SOURCE_SERVICE = "security-scanner"
        const val SCHEMA_VERSION = 1L
        const val MAX_EVENT_BYTES = 64 * 1024
        const val MAX_AFFECTED_SERVICES = 100
        const val MAX_SERVICE_LENGTH = 200
        const val METRIC_EVENTS = "openbank_context_projection_events_total"
        const val METRIC_DURATION = "openbank_context_projection_duration"
        const val METRIC_LAG = "openbank_context_projection_lag_seconds"
        val EVENT_TYPES = setOf(
            "ICT_INCIDENT_REPORTED",
            "ICT_INCIDENT_STATUS_CHANGED",
            "ICT_INCIDENT_REPORTED_TO_REGULATOR",
        )
    }
}

private fun JsonNode.text(name: String): String = path(name).takeIf { it.isTextual }?.asText()?.trim().orEmpty()
private fun JsonNode.long(name: String): Long = path(name).takeIf { it.canConvertToLong() }?.asLong() ?: 0

private fun incidentDigest(payload: String): String = MessageDigest.getInstance("SHA-256")
    .digest(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun incidentStableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

private data class IncidentProjectionEvent(
    val id: String,
    val severity: String,
    val status: String,
    val affectedServices: List<String>,
    val detectedAt: Instant,
    val occurredAt: Instant,
    val sourceVersion: Long,
    val eventType: String,
    val contentDigest: String,
) {
    val rootKey = "incident:$id"
    val eventKey = "incident:$id:$eventType:$sourceVersion"
}
