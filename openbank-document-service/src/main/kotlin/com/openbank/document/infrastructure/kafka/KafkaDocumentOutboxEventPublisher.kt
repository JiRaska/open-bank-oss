// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.TraceparentCapture
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.TracingMetadata
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KafkaDocumentOutboxEventPublisher(@Channel("document-events-out") private val emitter: MutinyEmitter<String>) :
    OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        var message = Message.of(entry.payload).addMetadata(meta)
        // The record's own producer span must be a child of the REQUEST that wrote the event, not of
        // this scheduler thread: the sink reads the parent from TracingMetadata, and the traceparent
        // header above is what a consumer's instrumentation extracts on the other side.
        TraceparentCapture.parentContext(entry.traceparent)?.let {
            message = message.addMetadata(TracingMetadata.withPrevious(it))
        }
        emitter.sendMessage(message).awaitSuspending()
    }
}
