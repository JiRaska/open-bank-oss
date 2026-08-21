// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** A post-commit Kafka redelivery must not add a second hash-chain entry. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AgentAuditRedeliveryIT {
    @Inject lateinit var repository: AuditRepository

    @Test
    fun `same producer event id is stored once across redelivery`() {
        val eventId = UUID.randomUUID()
        val entry = AuditEntry(
            id = eventId,
            eventType = "agent.run",
            aggregateType = "AI_AGENT",
            aggregateId = "agent:rca",
            actorId = "agent:rca",
            actorType = "AI_AGENT",
            payload = "{\"eventId\":\"$eventId\"}",
            sourceService = "agent-service",
            correlationId = "redelivery-$eventId",
            occurredAt = Instant.parse("2026-08-21T12:00:00Z"),
            recordedAt = Instant.parse("2026-08-21T12:00:00Z"),
            occurredAtSource = OccurredAtSource.EVENT,
        )

        onEventLoop {
            repository.save(entry)
            repository.save(entry)
        }

        assertThat(onEventLoop { repository.findByAggregateId("agent:rca") }.map { it.id })
            .containsExactly(eventId)
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
