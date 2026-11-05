// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.fincrime.ScreeningPolicy
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KafkaSanctionsOutboxEventPublisher(
    @Channel("sanctions-events-out") private val emitter: MutinyEmitter<String>,
    @Channel("oversight-events-out") private val oversightEmitter: MutinyEmitter<String>,
    private val objectMapper: ObjectMapper,
) : OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()

        if (entry.eventType != EVENT_SCREENED) return
        val payload = objectMapper.readTree(entry.payload)
        val status = payload.path("status").asText()
        val highRisk = status == "HIT" ||
            (
                status == "POTENTIAL_HIT" &&
                    payload.path("overallScore").asDouble() > ScreeningPolicy.POTENTIAL_HIT_BLOCK_THRESHOLD
                )
        if (!highRisk) return
        // A failure here leaves the outbox row unsent and retries both sends with the same ce-id.
        oversightEmitter.sendMessage(
            Message.of(minimalOversightPayload(payload)).addMetadata(meta),
        ).awaitSuspending()
    }

    /** Wake the sweep without copying screened names, identifiers or match details to its topic. */
    private fun minimalOversightPayload(payload: JsonNode): String {
        val trigger = objectMapper.createObjectNode()
            .put("sourceService", SOURCE_SERVICE)
            .put("occurredAt", payload.required("occurredAt").asText())
        return objectMapper.writeValueAsString(trigger)
    }

    private companion object {
        const val EVENT_SCREENED = "SanctionChecked"
        const val SOURCE_SERVICE = "sanctions-service"
    }
}
