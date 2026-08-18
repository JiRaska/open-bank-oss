// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.domain.event.DomesticPaymentStatusChangedEvent
import com.openbank.domestic.domain.event.toCreatedEvent
import com.openbank.domestic.domain.model.DomesticPayment
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
import java.time.Clock
import java.time.Instant

@ApplicationScoped
class KafkaDomesticPaymentEventPublisher(
    @Channel("events-out") private val emitter: MutinyEmitter<String>,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : DomesticPaymentEventPublisher,
    OutboxEventPublisher {

    override fun paymentCreatedPayload(payment: DomesticPayment): String =
        objectMapper.writeValueAsString(payment.toCreatedEvent(clock))

    override fun statusChangedPayload(previous: DomesticPayment, current: DomesticPayment): String =
        objectMapper.writeValueAsString(
            DomesticPaymentStatusChangedEvent(
                paymentId = current.id,
                previousStatus = previous.status,
                newStatus = current.status,
                rejectReason = current.rejectReason?.name,
                rejectDetail = current.rejectDetail,
                occurredAt = Instant.now(clock),
                eventType = "DOMESTIC_PAYMENT_STATUS_CHANGED",
                sourceService = "domestic-payment",
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
