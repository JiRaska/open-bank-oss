// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.cardissuance.infrastructure.kafka

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
 * Relays a card outbox row to Kafka (ADR-0050 N2/N3).
 *
 * - **Partition key = aggregate_id** so every event for one card lands on the same partition,
 *   preserving per-card ordering. A random key would scatter events across partitions and break
 *   downstream dedup/ordering (N2).
 * - **event.id carried as a header** (`ce-id` / `idempotency-key`) so at-least-once delivery is
 *   safely deduplicated by consumers, and `ce-type` carries the event type (ADR-0003 / N3).
 *   Headers use the canonical names from [OutboxKafkaHeaders].
 *
 * Implements [OutboxEventPublisher] (libs) so [CardOutboxDispatcher] can inject it through the
 * abstract base without a service-specific interface (ADR-0049 D3).
 */
@ApplicationScoped
class KafkaCardOutboxEventPublisher(@Channel("card-events-out") private val emitter: MutinyEmitter<String>) :
    OutboxEventPublisher {

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
