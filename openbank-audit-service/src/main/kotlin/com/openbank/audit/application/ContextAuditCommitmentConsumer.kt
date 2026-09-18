// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Strict, minimized Context commitment stream. Never invokes the legacy catch-and-ACK sink. */
@ApplicationScoped
class ContextAuditCommitmentConsumer(
    private val mapper: ObjectMapper,
    private val repository: AuditRepository,
    private val clock: Clock,
) {
    @Incoming("context-audit-commitments-in")
    suspend fun consume(message: Message<String>) {
        persist(message.payload)
        Uni.createFrom().completionStage(message.ack()).awaitSuspending()
    }

    internal suspend fun persist(payload: String) {
        require(payload.toByteArray().size <= MAX_PAYLOAD_BYTES) { "Context audit commitment is oversized" }
        val node = mapper.readTree(payload)
        require(node.isObject && node.fieldNames().asSequence().toSet() == FIELDS) {
            "Context audit commitment has unexpected fields"
        }
        require(node.required("schemaVersion").isIntegralNumber && node.path("schemaVersion").intValue() == 1)
        require(node.required("eventType").textValue() == EVENT_TYPE)
        require(node.required("aggregateType").textValue() == "CONTEXT_READ_AUDIT")
        require(node.required("sourceService").textValue() == "context-service")
        val id = UUID.fromString(requiredText(node, "eventId"))
        require(requiredText(node, "aggregateId") == id.toString())
        require(COMMITMENT.matches(requiredText(node, "commitment")))
        val occurredAt = Instant.parse(requiredText(node, "occurredAt"))
        repository.save(
            AuditEntry(
                id = id,
                eventType = EVENT_TYPE,
                aggregateType = "CONTEXT_READ_AUDIT",
                aggregateId = id.toString(),
                actorId = null,
                actorType = null,
                payload = payload,
                sourceService = node["sourceService"].asText(),
                correlationId = null,
                occurredAt = occurredAt,
                recordedAt = clock.instant(),
                occurredAtSource = OccurredAtSource.EVENT,
                sourceServiceSource = AttributionSource.EVENT,
            ),
        )
    }

    private fun requiredText(node: JsonNode, field: String): String {
        val value = node.required(field)
        require(value.isTextual && value.textValue().isNotBlank()) { "Missing Context audit commitment field" }
        return value.textValue()
    }

    companion object {
        private const val EVENT_TYPE = "CONTEXT_READ_AUDIT_COMMITTED"
        private const val MAX_PAYLOAD_BYTES = 2048
        private val COMMITMENT = Regex("[0-9a-f]{64}")
        private val FIELDS = setOf(
            "schemaVersion",
            "eventId",
            "eventType",
            "aggregateType",
            "aggregateId",
            "sourceService",
            "occurredAt",
            "commitment",
        )
    }
}
