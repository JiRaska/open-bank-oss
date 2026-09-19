// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyb.infrastructure.messaging

import com.openbank.kyb.domain.model.KybEvents
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

/** Strict event-to-topic routing: unknown types never fall through to the shared lifecycle topic. */
@ApplicationScoped
class KafkaKybOutboxEventPublisher(
    @Channel("kyb-events-out") private val lifecycle: MutinyEmitter<String>,
    @Channel("kyb-ubo-observation-references-out") private val ownershipReferences: MutinyEmitter<String>,
) : OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val emitter = when (entry.eventType) {
            UboObservationReference.EVENT_TYPE, UboObservationRestrictionReference.EVENT_TYPE -> ownershipReferences
            in LIFECYCLE_TYPES -> lifecycle
            else -> throw IllegalArgumentException("unsupported KYB outbox event type")
        }
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }

    private companion object {
        val LIFECYCLE_TYPES = setOf(
            KybEvents.STARTED,
            KybEvents.REGISTRY_VERIFIED,
            KybEvents.REVIEW_REQUIRED,
            KybEvents.SIGNER_INVITED,
            KybEvents.SIGNER_IDENTIFIED,
            KybEvents.AGREEMENT_SIGNED,
            KybEvents.COMPLETED,
            KybEvents.REJECTED,
            KybEvents.ABANDONED,
        )
    }
}
