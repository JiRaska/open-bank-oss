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
 * Dedicated D5 provenance stream. ACK follows the append-only commit. A persistence failure
 * NACKs the message; the channel's dead-letter strategy writes the failed record to its DLQ and
 * then commits the source offset. A retry from the original payload keeps the producer event ID,
 * and [AuditRepository] accepts an identical redelivery without appending another chain link.
 */
@ApplicationScoped
class AgentAuditConsumer {
    @Inject lateinit var auditConsumer: AuditConsumer

    @Incoming("agent-audit-events-in")
    @Suppress("TooGenericExceptionCaught") // Any audit-store failure must reach the Kafka channel's DLQ strategy.
    suspend fun consume(message: Message<String>) {
        try {
            auditConsumer.persist(message.payload)
        } catch (failure: Exception) {
            // Message<String> is manually acknowledged. An exception alone does not tell the
            // Kafka connector to apply this channel's dead-letter-queue failure strategy.
            Uni.createFrom().completionStage(message.nack(failure)).awaitSuspending()
            return
        }
        Uni.createFrom().completionStage(message.ack()).awaitSuspending()
    }
}
