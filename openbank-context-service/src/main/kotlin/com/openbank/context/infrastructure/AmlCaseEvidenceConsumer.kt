// SPDX-License-Identifier: Apache-2.0
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
class AmlCaseEvidenceConsumer(
    private val decoder: AmlCaseEventDecoder,
    private val history: AmlCaseHistoryRepository,
    private val meters: MeterRegistry,
) {
    @Incoming("aml-case-evidence-in")
    @Suppress("TooGenericExceptionCaught") // Every decode, storage, or ACK failure must NACK into the configured DLQ.
    suspend fun consume(message: Message<String>) {
        try {
            val metadata = requireNotNull(message.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)) {
                "AML evidence requires broker source metadata"
            }

            @Suppress("UNCHECKED_CAST")
            val record = metadata as IncomingKafkaRecordMetadata<Any?, String>
            val id = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID)?.value()?.toString(Charsets.UTF_8)
            val key = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)
                ?.value()?.toString(Charsets.UTF_8)
            require(id != null && id == key) { "AML event identity headers disagree" }
            val type = record.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
                ?.value()?.toString(Charsets.UTF_8)
            require(record.topic == "openbank.aml.events") { "unexpected AML evidence topic" }
            val evidence = decoder.decode(message.payload, id, type)
            require(record.key == evidence.caseId.toString()) { "AML event case and broker key disagree" }
            history.append(evidence)
            Uni.createFrom().completionStage(message.ack()).awaitSuspending()
            meters.counter("openbank_context_aml_case_events", "outcome", "recorded").increment()
        } catch (failure: Exception) {
            meters.counter("openbank_context_aml_case_events", "outcome", "failed").increment()
            Uni.createFrom().completionStage(message.nack(failure)).awaitSuspending()
        }
    }
}
