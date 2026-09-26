// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.infrastructure.kafka

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
 * Routes each outbox row to its topic by event type: fixings to `fx-fixing-out`
 * (`openbank.fx.fixing.published`), everything else to `fx-events-out`
 * (`openbank.fx.conversion.completed`). The outbox is shared; the topics are not.
 */
@ApplicationScoped
class KafkaFxOutboxEventPublisher(
    @Channel("fx-events-out") private val emitter: MutinyEmitter<String>,
    @Channel("fx-fixing-out") private val fixingEmitter: MutinyEmitter<String>,
) : OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        val target = if (entry.eventType.startsWith(FIXING_EVENT_PREFIX)) fixingEmitter else emitter
        target.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }

    private companion object {
        const val FIXING_EVENT_PREFIX = "fx.fixing."
    }
}
