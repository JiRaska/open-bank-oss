// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import java.time.Instant
import java.util.UUID

/** Audit-only case lifecycle stream. Storage failure never acknowledges the source event. */
@ApplicationScoped
class FraudCaseAuditConsumer(private val mapper: ObjectMapper, private val audit: AuditConsumer) {
    @Incoming("fraud-case-audit-in")
    suspend fun consume(message: Message<String>) {
        validate(message.payload)
        audit.persist(message.payload)
        Uni.createFrom().completionStage(message.ack()).awaitSuspending()
    }

    internal fun validate(payload: String) {
        val node = mapper.readTree(payload)
        require(node != null && node.isObject && node.fieldNames().asSequence().toSet() == FIELDS) {
            "invalid Fraud case audit envelope"
        }
        val type = node.path("eventType").asText()
        require(type in TYPES) { "invalid Fraud case audit type" }
        require(node.path("sourceService").asText() == "fraud-service") { "invalid source" }
        require(node.path("aggregateType").asText() == "FRAUD_CASE") { "invalid aggregate type" }
        require(node.path("eventId").isTextual && node.path("aggregateId").isTextual) {
            "event and case IDs are required"
        }
        UUID.fromString(node.path("eventId").asText())
        UUID.fromString(node.path("aggregateId").asText())
        require(node.path("occurredAt").isTextual) { "event time is required" }
        Instant.parse(node.path("occurredAt").asText())
        require(node.path("actorId").isTextual && node.path("actorId").asText().isNotBlank()) {
            "actor is required"
        }
        require(node.path("revision").isIntegralNumber) { "revision must be an integer" }
        val revision = node.path("revision").asLong(-1)
        require(revision > 0) { "revision is required" }
    }

    private companion object {
        val FIELDS = setOf(
            "eventId",
            "eventType",
            "aggregateId",
            "actorId",
            "revision",
            "occurredAt",
            "aggregateType",
            "sourceService",
        )
        val TYPES = setOf("fraud.case_opened.audit", "fraud.case_closed.audit")
    }
}
