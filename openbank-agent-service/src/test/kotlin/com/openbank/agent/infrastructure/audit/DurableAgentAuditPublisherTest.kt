// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.audit.AuditEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DurableAgentAuditPublisherTest {
    private val outbox = mockk<AgentAuditOutbox>()

    @Test
    fun `publisher records the producer event id before any Kafka handoff`(): Unit = runBlocking {
        val event =
            AuditEvent(
                actorId = "agent:rca",
                actorType = "AI_AGENT",
                operation = "agent.run",
                resourceType = "INCIDENT",
                resourceId = "inc-1",
            )
        every { outbox.enqueue(any(), any()) } returns Unit

        DurableAgentAuditPublisher(outbox, jacksonObjectMapper().findAndRegisterModules()).publish(event)

        verify {
            outbox.enqueue(
                event.eventId,
                match {
                    it.contains("\"eventId\":\"${event.eventId}\"") &&
                        it.contains("\"sourceService\":\"agent-service\"")
                },
            )
        }
    }

    @Test
    fun `publisher sends a null aggregateId rather than substituting the actor when there is no resource`(): Unit =
        runBlocking {
            val event =
                AuditEvent(
                    actorId = "agent:rca",
                    actorType = "AI_AGENT",
                    operation = "agent.run",
                    resourceType = "INCIDENT",
                    resourceId = null,
                )
            every { outbox.enqueue(any(), any()) } returns Unit

            DurableAgentAuditPublisher(outbox, jacksonObjectMapper().findAndRegisterModules()).publish(event)

            verify {
                outbox.enqueue(
                    event.eventId,
                    match {
                        it.contains("\"aggregateId\":null") && !it.contains("\"aggregateId\":\"agent:rca\"")
                    },
                )
            }
        }

    @Test
    fun `dispatcher retains outbox row when Kafka rejects it`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val emitter = mockk<Emitter<Record<String, String>>>()
        val emitterInstance = mockk<Instance<Emitter<Record<String, String>>>>()
        every { outbox.claim(25) } returns listOf(AgentAuditOutbox.Claimed(id, "{}"))
        every { emitterInstance.get() } returns emitter
        every { emitter.send(any()) } returns
            CompletableFuture.failedFuture(IllegalStateException("broker unavailable"))
        every { outbox.failed(any(), any()) } returns 1

        AgentAuditOutboxDispatcher(outbox, emitterInstance, true).dispatch()

        verify { outbox.failed(id, match { it.contains("broker unavailable") }) }
        verify(exactly = 0) { outbox.published(any()) }
    }

    @Test
    fun `disabled dispatcher does not resolve an unconfigured outgoing channel`(): Unit = runBlocking {
        val emitterInstance = mockk<Instance<Emitter<Record<String, String>>>>()

        AgentAuditOutboxDispatcher(outbox, emitterInstance, false).dispatch()

        verify(exactly = 0) { outbox.claim(any()) }
        verify(exactly = 0) { emitterInstance.get() }
    }
}
