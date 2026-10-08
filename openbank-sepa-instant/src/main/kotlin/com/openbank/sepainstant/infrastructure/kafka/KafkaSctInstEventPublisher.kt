// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KafkaSctInstEventPublisher @Inject constructor(
    @Channel("sct-inst-events-out") private val emitter: MutinyEmitter<String>,
) : OutboxEventPublisher {

    /**
     * Keep the established four-field body; key by the payment (aggregate) id so per-payment order
     * holds, and expose the durable event ID as ce-id/idempotency-key on every retry.
     */
    override suspend fun publish(entry: OutboxEntry) {
        emitter.sendMessage(messageFor(entry)).awaitSuspending()
    }

    internal fun messageFor(entry: OutboxEntry): Message<String> {
        val headers = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (name, value) ->
            headers.add(name, value.toByteArray(Charsets.UTF_8))
        }
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(entry.aggregateId.toString())
            .withHeaders(headers)
            .build()
        return Message.of(entry.payload).addMetadata(metadata)
    }
}
