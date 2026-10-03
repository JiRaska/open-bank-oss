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

/** Consumes only case lifecycle pointers. No customer relationship is inferred from a pointer. */
@ApplicationScoped
class FraudCaseReferenceConsumer(
    private val decoder: FraudCaseReferenceDecoder,
    private val references: FraudCaseReferenceRepository,
    private val meters: MeterRegistry,
) {
    @Incoming("fraud-case-references-in")
    @Suppress("TooGenericExceptionCaught") // Invalid references are nacked into this channel's isolated DLQ.
    suspend fun consume(message: Message<String>) {
        try {
            val metadata = requireNotNull(message.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)) {
                "Fraud case reference requires broker metadata"
            }

            @Suppress("UNCHECKED_CAST")
            val record = metadata as IncomingKafkaRecordMetadata<Any?, String>
            require(record.topic == TOPIC) { "unexpected Fraud case reference topic" }
            val id = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID)?.value()?.toString(Charsets.UTF_8)
            val idempotencyKey = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)
                ?.value()?.toString(Charsets.UTF_8)
            require(id != null && id == idempotencyKey) { "Fraud case reference identity headers disagree" }
            val type = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
                ?.value()?.toString(Charsets.UTF_8)
            val reference = decoder.decode(message.payload, id, type)
            require(record.key == reference.caseId.toString()) { "Fraud case reference key disagrees with case" }
            references.append(reference)
            Uni.createFrom().completionStage(message.ack()).awaitSuspending()
            meters.counter(METRIC, "outcome", "recorded").increment()
        } catch (failure: Exception) {
            meters.counter(METRIC, "outcome", "failed").increment()
            Uni.createFrom().completionStage(message.nack(failure)).awaitSuspending()
        }
    }

    private companion object {
        const val TOPIC = "openbank.fraud.investigation.case.references"
        const val METRIC = "openbank_context_fraud_case_reference_events"
    }
}
