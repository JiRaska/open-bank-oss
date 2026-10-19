// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.swift.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.swift.domain.institution.InstitutionTransferStatusChanged
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Publishes swift's outbox rows. `swift.institution-transfer.status.v1` rows go to the
 * `swift-institution-transfers-out` channel (`openbank.swift.institution-transfers`, contract
 * 9.2); every other event type stays on `swift-events-out`.
 */
@ApplicationScoped
class KafkaSwiftOutboxEventPublisher(
    @Channel("swift-events-out") private val emitter: MutinyEmitter<String>,
    @Channel("swift-institution-transfers-out") private val institutionTransferEmitter: MutinyEmitter<String>,
) : OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        val target = if (entry.eventType ==
            InstitutionTransferStatusChanged.EVENT_TYPE
        ) {
            institutionTransferEmitter
        } else {
            emitter
        }
        target.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }
}
