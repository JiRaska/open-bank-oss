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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** JDBC outbox: an audit call returns only after the source database has acknowledged its row. */
@ApplicationScoped
class AgentAuditOutbox(private val dataSource: DataSource) {
    data class Claimed(val eventId: UUID, val payload: String)

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
        try {
            val rows = c.prepareStatement(
                "SELECT event_id, payload FROM agent_audit_outbox WHERE published_at IS NULL AND (claimed_at IS NULL OR claimed_at < NOW() - INTERVAL '1 minute') ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT ?",
            ).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Claimed(rs.getObject(1, UUID::class.java), rs.getString(2))) }
                }
            }
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
            c.commit()
            rows
        } catch (e: Exception) {
            c.rollback()
            throw e
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
        error.take(500),
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

/** Replaces the log-only fallback in agent-service with an acknowledged local durable handoff. */
@ApplicationScoped
@Alternative
@Priority(100)
class DurableAgentAuditPublisher @Inject constructor(
    private val outbox: AgentAuditOutbox,
    private val objectMapper: ObjectMapper,
) : AuditEventPublisher {
    override suspend fun publish(event: AuditEvent) {
        outbox.enqueue(
            event.eventId,
            objectMapper.writeValueAsString(
                event.payload + mapOf(
                    "eventId" to event.eventId, "eventType" to event.operation, "aggregateType" to event.resourceType,
                    "aggregateId" to (event.resourceId ?: event.actorId), "actorId" to event.actorId,
                    "actorType" to event.actorType,
                    "sourceService" to "agent-service", "correlationId" to event.traceId,
                    "occurredAt" to event.timestamp,
                    "channel" to event.channel, "actChain" to event.actChain, "sessionId" to event.sessionId,
                    "result" to event.result.name,
                ),
            ),
        )
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
