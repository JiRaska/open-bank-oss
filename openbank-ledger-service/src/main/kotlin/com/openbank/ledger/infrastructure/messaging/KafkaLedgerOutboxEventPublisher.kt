// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.ledger.infrastructure.messaging

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
 * Relays an outbox row to Kafka (ADR-0050 N2/N3).
 *
 * - **Partition key = aggregate_id** so every event for one ledger aggregate lands on the same
 *   partition, preserving per-account ordering (a random key would scatter postings and break
 *   downstream dedup/ordering — N2).
 * - **event.id carried as a header** (`ce-id` / `idempotency-key`) so at-least-once delivery is
 *   safely deduplicated by consumers, exactly as ADR-0003 mandates (N3).
 *
 * Resilience annotations (@Bulkhead, @CircuitBreaker, @Retry, @Timeout) are placed on the
 * concrete [com.openbank.ledger.infrastructure.outbox.LedgerOutboxDispatcher.publishWithResilience]
 * override — CDI interceptors only fire when the call enters through the proxy of the concrete bean
 * (ADR-0013 / AbstractOutboxDispatcher KDoc).
 */
@ApplicationScoped
class KafkaLedgerOutboxEventPublisher(@Channel("ledger-events-out") private val emitter: MutinyEmitter<String>) :
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
