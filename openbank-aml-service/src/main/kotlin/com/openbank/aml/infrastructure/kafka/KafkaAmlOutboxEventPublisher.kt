// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Relays an AML outbox row to Kafka (ADR-0050 N2/N3).
 *
 * - **Partition key = aggregate_id** so every event for one AML case lands on the same partition,
 *   preserving per-case ordering. A random key would scatter events across partitions and break
 *   downstream dedup/ordering (N2).
 * - **event.id carried as a header** (`ce-id` / `idempotency-key`) so at-least-once delivery is
 *   safely deduplicated by consumers, and `ce-type` carries the event type (ADR-0003 / N3).
 *
 * The publish is wrapped in MicroProfile Fault Tolerance on the dispatcher side. Because this bean
 * is injected into the dispatcher (a separate CDI bean), the call is proxied and the resilience
 * policies actually fire (interceptors only apply to cross-bean calls).
 */
@ApplicationScoped
class KafkaAmlOutboxEventPublisher(
    @Channel("aml-events-out") private val emitter: MutinyEmitter<String>,
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

        if (entry.eventType != EVENT_CASE_CREATED) return
        val payload = objectMapper.readTree(entry.payload)
        if (payload.path("riskLevel").asText() !in ELEVATED_RISKS) return
        // A failure here leaves the outbox row unsent and retries both sends with the same ce-id.
        oversightEmitter.sendMessage(
            Message.of(minimalOversightPayload(payload)).addMetadata(meta),
        ).awaitSuspending()
    }

    /** Wake the sweep without copying customer identifiers, references or alert details to its topic. */
    private fun minimalOversightPayload(payload: JsonNode): String {
        val trigger = objectMapper.createObjectNode()
            .put("sourceService", SOURCE_SERVICE)
            .put("occurredAt", payload.required("occurredAt").asText())
        return objectMapper.writeValueAsString(trigger)
    }

    private companion object {
        const val EVENT_CASE_CREATED = "aml.case.created.v1"
        const val SOURCE_SERVICE = "aml-service"
        val ELEVATED_RISKS = setOf("HIGH", "CRITICAL")
    }
}
