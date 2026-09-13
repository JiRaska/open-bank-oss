// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** JDBC outbox: an audit call returns only after the source database has acknowledged its row. */
@ApplicationScoped
class AgentAuditOutbox(private val dataSource: DataSource) {
    data class Claimed(val eventId: UUID, val payload: String)

    private companion object {
        /** `last_error` truncation — long enough to identify the failure, short enough to never
         * itself be why a row's write fails. */
        const val LAST_ERROR_MAX_LENGTH = 500
    }

    fun enqueue(eventId: UUID, payload: String) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO agent_audit_outbox (event_id, payload) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING",
            ).use { ps ->
                ps.setObject(1, eventId)
                ps.setString(2, payload)
                ps.executeUpdate()
            }
        }
    }

    fun claim(limit: Int): List<Claimed> = dataSource.connection.use { c ->
        c.autoCommit = false
        // TooGenericExceptionCaught: any failure between the claiming SELECT and the commit must
        // roll back — a JDBC driver can throw SQLException, PSQLException or a runtime failure
        // from the connection pool, and every one of them leaves a claim half-applied unless
        // this rolls back regardless of type.
        @Suppress("TooGenericExceptionCaught")
        try {
            val rows = selectUnclaimed(c, limit)
            markClaimed(c, rows)
            c.commit()
            rows
        } catch (e: Exception) {
            c.rollback()
            throw e
        }
    }

    private fun selectUnclaimed(c: Connection, limit: Int): List<Claimed> = c.prepareStatement(
        "SELECT event_id, payload FROM agent_audit_outbox WHERE published_at IS NULL AND (claimed_at IS NULL OR claimed_at < NOW() - INTERVAL '1 minute') ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT ?",
    ).use { ps ->
        ps.setInt(1, limit)
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(Claimed(rs.getObject(1, UUID::class.java), rs.getString(2))) }
        }
    }

    private fun markClaimed(c: Connection, rows: List<Claimed>) {
        c.prepareStatement(
            "UPDATE agent_audit_outbox SET claimed_at = ?, publish_attempts = publish_attempts + 1 WHERE event_id = ?",
        ).use { ps ->
            rows.forEach { row ->
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
                ps.setObject(2, row.eventId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun published(eventId: UUID) = update(
        eventId,
        "UPDATE agent_audit_outbox SET published_at = ?, claimed_at = NULL, last_error = NULL WHERE event_id = ?",
        null,
    )
    fun failed(eventId: UUID, error: String) = update(
        eventId,
        "UPDATE agent_audit_outbox SET claimed_at = NULL, last_error = ? WHERE event_id = ?",
        error.take(LAST_ERROR_MAX_LENGTH),
    )
    private fun update(eventId: UUID, sql: String, error: String?) = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            if (error ==
                null
            ) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
            } else {
                ps.setString(1, error)
            }
            ps.setObject(2, eventId)
            ps.executeUpdate()
        }
    }
}

/**
 * The fixed envelope fields this publisher always sends, on top of which [publish] splices the
 * audited operation's own free-form [AuditEvent.payload] — so the Kafka message is these fields
 * plus whatever the caller passed, not a subset of either. A real `data class` rather than an
 * inline map so the AsyncAPI contract (`openbank-contracts/openbank-agent-service/asyncapi.yaml`)
 * has a concrete producer to check itself against.
 *
 * [EVENT_TYPE] names this ENVELOPE shape on the wire — the one message definition this class
 * ever produces — and is distinct from the [eventType] field inside it, which carries the
 * audited operation's own name ([AuditEvent.operation]) and varies per call site. There is no
 * single literal for the business `eventType` to be named after; [EVENT_TYPE] gives the contract
 * gate a fixed literal for the envelope itself instead.
 */
data class AgentAuditEventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val aggregateType: String,
    /**
     * `null` when the audited operation has no distinct resource — never a substitute for one.
     * [DurableAgentAuditPublisher.publish] used to fall back to [actorId] here, which made an
     * agent action with no resource store the ACTOR's id as the aggregate, indistinguishable
     * from a real declaration. A null lets `AuditConsumer.resolveAggregateId` run its own
     * inference chain and, failing that, record the honest `ABSENT` provenance instead (#6479).
     */
    val aggregateId: String?,
    val actorId: String,
    val actorType: String,
    val sourceService: String,
    val correlationId: String?,
    val occurredAt: Instant,
    val channel: String?,
    val actChain: List<String>,
    val sessionId: String?,
    val result: String,
) {
    companion object {
        const val EVENT_TYPE = "agent-audit-event"
    }
}

/** Replaces the log-only fallback in agent-service with an acknowledged local durable handoff. */
@ApplicationScoped
@Alternative
@Priority(DurableAgentAuditPublisher.ALTERNATIVE_PRIORITY)
class DurableAgentAuditPublisher @Inject constructor(
    private val outbox: AgentAuditOutbox,
    private val objectMapper: ObjectMapper,
) : AuditEventPublisher {
    companion object {
        /** Same convention as `RedisNonceStore.ALTERNATIVE_PRIORITY`. */
        const val ALTERNATIVE_PRIORITY = 100
    }

    override suspend fun publish(event: AuditEvent) {
        val envelope = AgentAuditEventEnvelope(
            eventId = event.eventId,
            eventType = event.operation,
            aggregateType = event.resourceType,
            aggregateId = event.resourceId,
            actorId = event.actorId,
            actorType = event.actorType,
            sourceService = "agent-service",
            correlationId = event.traceId,
            occurredAt = event.timestamp,
            channel = event.channel,
            actChain = event.actChain,
            sessionId = event.sessionId,
            result = event.result.name,
        )

        @Suppress("UNCHECKED_CAST")
        val envelopeFields = objectMapper.convertValue(envelope, Map::class.java) as Map<String, Any?>
        outbox.enqueue(event.eventId, objectMapper.writeValueAsString(event.payload + envelopeFields))
    }
}

@ApplicationScoped
class AgentAuditOutboxDispatcher @Inject constructor(
    private val outbox: AgentAuditOutbox,
    // Do not resolve the channel while transport is disabled. A disabled SmallRye outgoing
    // channel has no connector, and eagerly injecting its Emitter makes every test/runtime
    // scheduler attempt fail before this class can return at the feature gate.
    @Channel("agent-audit-events-out") private val emitter: Instance<Emitter<Record<String, String>>>,
    @ConfigProperty(name = "agent.audit.kafka.enabled", defaultValue = "false") private val enabled: Boolean,
) {
    @Scheduled(
        every = "\${agent.audit.outbox.poll-interval:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    // TooGenericExceptionCaught: a Kafka send can fail with anything from a serializer error to
    // a broker-unreachable timeout, and every one of them must record `last_error` and leave the
    // row claimed for retry rather than crash the scheduled sweep for every other claimed row.
    @Suppress("TooGenericExceptionCaught")
    suspend fun dispatch() {
        if (!enabled) return
        outbox.claim(BATCH_SIZE).forEach { row ->
            try {
                Uni.createFrom().completionStage(
                    emitter.get().send(Record.of(row.eventId.toString(), row.payload)),
                ).awaitSuspending()
                outbox.published(row.eventId)
            } catch (
                e: Exception,
            ) {
                outbox.failed(row.eventId, e.message ?: e.javaClass.simpleName)
            }
        }
    }
    private companion object {
        const val BATCH_SIZE = 25
    }
}
