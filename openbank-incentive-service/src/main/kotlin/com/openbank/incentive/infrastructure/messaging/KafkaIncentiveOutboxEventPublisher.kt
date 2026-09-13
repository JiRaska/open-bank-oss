// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.messaging

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

@ApplicationScoped
class KafkaIncentiveOutboxEventPublisher(@Channel("incentive-events-out") private val emitter: MutinyEmitter<String>) :
    OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
        val headers = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (name, value) -> headers.add(name, value.toByteArray()) }
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(headers)
            .build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(metadata)).awaitSuspending()
    }
}
