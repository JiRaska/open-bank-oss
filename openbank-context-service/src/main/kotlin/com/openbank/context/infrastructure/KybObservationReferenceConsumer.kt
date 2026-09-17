// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KybObservationReferenceConsumer(
    private val decoder: KybObservationReferenceDecoder,
    private val references: KybObservationReferenceRepository,
    private val meters: MeterRegistry,
) {
    @Incoming("kyb-ubo-observation-references-in")
    @Suppress("TooGenericExceptionCaught") // Every decode, storage or ACK failure NACKs into the isolated DLQ.
    suspend fun consume(message: Message<String>) {
        try {
            val metadata = requireNotNull(message.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)) {
                "KYB reference requires broker source metadata"
            }

            @Suppress("UNCHECKED_CAST")
            val record = metadata as IncomingKafkaRecordMetadata<Any?, String>
            require(record.topic == TOPIC) { "unexpected KYB reference topic" }
            val id = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID)?.value()?.toString(Charsets.UTF_8)
            val key = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)
                ?.value()?.toString(Charsets.UTF_8)
            require(id != null && id == key) { "KYB reference identity headers disagree" }
            val type = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
                ?.value()?.toString(Charsets.UTF_8)
            val reference = decoder.decode(message.payload, id, type)
            require(record.key == reference.caseId.toString()) { "KYB reference case and broker key disagree" }
            references.append(reference)
            Uni.createFrom().completionStage(message.ack()).awaitSuspending()
            meters.counter("openbank_context_kyb_reference_events", "outcome", "recorded").increment()
        } catch (failure: Exception) {
            meters.counter("openbank_context_kyb_reference_events", "outcome", "failed").increment()
            Uni.createFrom().completionStage(message.nack(failure)).awaitSuspending()
        }
    }

    private companion object {
        const val TOPIC = "openbank.kyb.ubo-observation-references"
    }
}
