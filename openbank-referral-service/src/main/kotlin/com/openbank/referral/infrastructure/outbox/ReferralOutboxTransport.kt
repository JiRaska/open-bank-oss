// SPDX-License-Identifier: Apache-2.0
package com.openbank.referral.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KafkaReferralOutboxPublisher(@Channel("referral-outbox-out") private val emitter: MutinyEmitter<String>) :
    OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
        val headers = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (key, value) -> headers.add(key, value.toByteArray()) }
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry)).withHeaders(headers).build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(metadata)).awaitSuspending()
    }
}

@ApplicationScoped
class ReferralOutboxDispatcher(
    private val repository: ReferralOutboxRepository,
    private val publisher: OutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false")
    private val enabled: Boolean,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    override val outboxRepository: OutboxRepository get() = repository
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "referral-outbox-dispatcher",
    )
    suspend fun dispatch() {
        if (enabled) dispatchScheduledBatch()
    }
}
