// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Dedicated D5 provenance stream. This handler ACKs only after the append-only audit store
 * has committed the producer event id, and NACKs on failure so the connector's configured
 * failure strategy (dead-letter-queue) parks the record; [AuditRepository] de-duplicates a
 * post-commit redelivery by that same id.
 *
 * The failure is NACKED, not rethrown, deliberately. A method that takes `Message` and
 * returns `Uni` infers MANUAL acknowledgment, and under MANUAL a thrown exception never
 * reaches `message.nack` — it propagates as a stream error, which cancels the Kafka source
 * instead of parking the record (verified against Redpanda in
 * AgentAuditKafkaPersistenceIT: a rethrowing consumer wedged the partition and the DLQ
 * received nothing). The legacy [AuditConsumer] nacks in its catch block for the same
 * reason; this consumer mirrors it.
 */
@ApplicationScoped
class AgentAuditConsumer {
    @Inject lateinit var auditConsumer: AuditConsumer

    @Incoming("agent-audit-events-in")
    @Suppress("TooGenericExceptionCaught") // Any parse/store failure must NACK at this transport boundary.
    suspend fun consume(message: Message<String>) {
        try {
            auditConsumer.persist(message.payload)
        } catch (failure: Exception) {
            // NACK hands the record to the connector's dead-letter-queue strategy.
            // Do not log the event body: audit payloads may contain personal or financial data.
            Uni.createFrom().completionStage(message.nack(failure)).awaitSuspending()
            return
        }
        // Keep ACK outside the persistence catch. A lost ACK must not turn a committed row into
        // a poison-record decision; broker redelivery is deduplicated by the immutable entry ID.
        Uni.createFrom().completionStage(message.ack()).awaitSuspending()
    }
}
