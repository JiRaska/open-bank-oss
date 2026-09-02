// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.domain.event.SepaPaymentReturnedEvent
import com.openbank.sepa.domain.event.toCreatedEvent
import com.openbank.sepa.domain.event.toStatusChangedEvent
import com.openbank.sepa.domain.model.SepaPayment
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KafkaSepaPaymentEventPublisher(
    @Channel("events-out") private val emitter: MutinyEmitter<String>,
    private val objectMapper: ObjectMapper,
) : SepaPaymentEventPublisher,
    OutboxEventPublisher {

    override fun paymentCreatedPayload(payment: SepaPayment): String =
        objectMapper.writeValueAsString(payment.toCreatedEvent(java.time.Clock.systemUTC()))

    override fun statusChangedPayload(previous: SepaPayment, current: SepaPayment): String =
        objectMapper.writeValueAsString(current.toStatusChangedEvent(previous.status, java.time.Clock.systemUTC()))

    override fun returnEvidencePayload(
        payment: SepaPayment,
        originalEndToEndId: String,
        returnReasonCode: String?,
        actorId: String,
        actorType: String,
        correlationId: String?,
        reversalPerformed: Boolean,
    ): String = objectMapper.writeValueAsString(
        SepaPaymentReturnedEvent(
            paymentId = payment.id,
            originalEndToEndId = originalEndToEndId,
            returnReasonCode = returnReasonCode,
            actorId = actorId,
            actorType = actorType,
            correlationId = correlationId,
            reversalPerformed = reversalPerformed,
            occurredAt = java.time.Instant.now(java.time.Clock.systemUTC()),
        ),
    )

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }
}
