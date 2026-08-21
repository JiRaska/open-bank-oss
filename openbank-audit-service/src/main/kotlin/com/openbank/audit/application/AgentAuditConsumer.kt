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
 * Dedicated D5 provenance stream. Unlike the legacy mixed audit stream, this handler ACKs only
 * after the append-only audit store has committed the producer event id. A failure therefore
 * leaves the Kafka offset uncommitted for retry; [AuditRepository] de-duplicates a post-commit
 * retry by that same id.
 */
@ApplicationScoped
class AgentAuditConsumer {
    @Inject lateinit var auditConsumer: AuditConsumer

    @Incoming("agent-audit-events-in")
    suspend fun consume(message: Message<String>) {
        auditConsumer.persist(message.payload)
        Uni.createFrom().completionStage(message.ack()).awaitSuspending()
    }
}
