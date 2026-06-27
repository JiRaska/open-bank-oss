// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Relays an interest outbox row to Kafka (ADR-0050 N2/N3).
 *
 * - **Partition key = aggregate_id** so every event for one accrual aggregate lands on the same
 *   partition, preserving per-account ordering. A random key would scatter events across
 *   partitions and break downstream dedup/ordering (N2).
 * - **event.id carried as a header** (`ce-id` / `idempotency-key`) so at-least-once delivery is
 *   safely deduplicated by consumers, and `ce-type` carries the event type (ADR-0003 / N3).
 *
 * Implements [OutboxEventPublisher] from libs (ADR-0049 D3) so the resilience annotations
 * applied on the dispatcher side fire through CDI proxying (cross-bean call).
 */
@ApplicationScoped
class KafkaInterestOutboxEventPublisher(
    @Channel("interest-events-out") private val emitter: MutinyEmitter<String>,
) :
    OutboxEventPublisher {

    @Bulkhead(value = 1, waitingTaskQueue = 1)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @Timeout(3000)
    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(metadata)).awaitSuspending()
    }
}
