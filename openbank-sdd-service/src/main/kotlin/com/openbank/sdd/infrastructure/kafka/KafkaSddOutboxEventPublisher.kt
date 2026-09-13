// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import org.jboss.logging.Logger

/**
 * Relays an SDD outbox row to Kafka (ADR-0050 N2/N3).
 *
 * - **Partition key = aggregate_id** so every event for one mandate aggregate lands on the same
 *   partition, preserving per-mandate ordering (N2).
 * - **event.id carried as headers** (`ce-id` / `idempotency-key` / `ce-type`) so at-least-once
 *   delivery is safely deduplicated by consumers (N3). See [OutboxKafkaHeaders] for canonical names.
 */
@ApplicationScoped
class KafkaSddOutboxEventPublisher(
    @Channel("sdd-events-out") private val emitter: MutinyEmitter<String>,
    private val objectMapper: ObjectMapper,
) : OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitter.sendMessage(Message.of(withSourceService(entry.payload)).addMetadata(meta)).awaitSuspending()
    }

    /**
     * Stamps the producer's own name onto the outgoing payload as `sourceService`.
     *
     * WHY HERE AND NOT ON THE EVENT TYPE. audit-service resolves attribution strongest-claim-first:
     * a `sourceService` on the event body is `AttributionSource.EVENT`, and without it the row falls
     * back to the topic ladder (`TopicAttribution`) and is recorded as `AttributionSource.TOPIC` --
     * a value DERIVED from the topic name rather than STATED by the producer. Both spell
     * "sdd-service" today, so this is not a wrong value being corrected; it is an inferred one
     * becoming a declared one. That distinction cannot be repaired later: `audit_entries` is
     * append-only AT THE DATABASE (V2's rules are `DO INSTEAD NOTHING`, so a normalising UPDATE
     * touches zero rows and REPORTS SUCCESS) and `source_service` is chain-hashed into
     * `record_hash`. Every day the field is absent produces rows whose attribution is permanently
     * inferred, so the fix is forward-only by construction.
     *
     * The stamp lives on the publisher, not on each event data class, because this module has no
     * shared event supertype -- a per-class field would have to be repeated on every event type and
     * silently omitted by the next one added. The channel has exactly one exit and this is it.
     *
     * The literal key matters: the payload is a serialised data class, so the wire key exists only
     * as a Kotlin property name at runtime and a quoted-string probe over this module finds nothing.
     * Writing it through an ObjectNode is what makes the claim greppable by a quoted-key probe as
     * well as visible to one that reads the emitting type -- the fleet has both, and a claim only
     * one of them can see is a claim that gets miscounted.
     *
     * A payload that is not a JSON object is emitted unchanged and logged: this is the money path,
     * and refusing to publish would be a strictly worse failure than an unattributed row.
     */
    private fun withSourceService(payload: String): String = try {
        when (val node = objectMapper.readTree(payload)) {
            is ObjectNode -> objectMapper.writeValueAsString(node.put("sourceService", SOURCE_SERVICE))
            else -> payload.also { log.warn("outbox payload is not a JSON object; emitting without sourceService") }
        }
    } catch (e: JsonProcessingException) {
        log.warn("outbox payload is not parseable JSON; emitting without sourceService", e)
        payload
    }

    companion object {
        /**
         * The module directory name minus the `openbank-` prefix -- the fleet's audit convention,
         * and the same spelling `TopicAttribution` already derives for this topic.
         */
        internal const val SOURCE_SERVICE = "sdd-service"

        private val log: Logger = Logger.getLogger(KafkaSddOutboxEventPublisher::class.java)
    }
}
