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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message

/** Stores approved guarantee pointers only. Kafka key is contractId, not loanId. */
@ApplicationScoped
class LendingGuaranteeReferenceConsumer(
    private val decoder: LendingGuaranteeReferenceDecoder,
    private val references: LendingGuaranteeReferenceRepository,
    private val meters: MeterRegistry,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
) {
    @Incoming("lending-guarantee-references-in")
    @Suppress("TooGenericExceptionCaught") // Rejects go to this channel's isolated DLQ.
    suspend fun consume(message: Message<String>) {
        try {
            val metadata = requireNotNull(message.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)) {
                "Lending reference requires broker metadata"
            }

            @Suppress("UNCHECKED_CAST")
            val record = metadata as IncomingKafkaRecordMetadata<Any?, String>
            require(record.topic == TOPIC) { "unexpected Lending reference topic" }
            require(record.key is String && UUID_PATTERN.matches(record.key as String)) {
                "invalid Lending reference contract key"
            }
            val id = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID)?.value()?.toString(Charsets.UTF_8)
            val idempotencyKey = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)
                ?.value()?.toString(Charsets.UTF_8)
            require(id != null && id == idempotencyKey) { "Lending reference identity headers disagree" }
            val type = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
                ?.value()?.toString(Charsets.UTF_8)
            val reference = decoder.decode(message.payload, id, type)
            require(reference.bankScope == bankScope) { "cross-bank Lending reference" }
            references.append(reference)
            Uni.createFrom().completionStage(message.ack()).awaitSuspending()
            meters.counter(METRIC, "outcome", "recorded").increment()
        } catch (failure: Exception) {
            meters.counter(METRIC, "outcome", "failed").increment()
            Uni.createFrom().completionStage(message.nack(failure)).awaitSuspending()
        }
    }

    private companion object {
        const val TOPIC = "openbank.lending.graph.references"
        const val METRIC = "openbank_context_lending_guarantee_reference_events"
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
