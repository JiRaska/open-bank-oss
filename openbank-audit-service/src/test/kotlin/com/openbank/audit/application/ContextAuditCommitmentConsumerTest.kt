// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ContextAuditCommitmentConsumerTest {
    private val repository = mockk<AuditRepository>()
    private val consumer = ContextAuditCommitmentConsumer(
        jacksonObjectMapper(),
        repository,
        Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `accepts only a minimized commitment and acknowledges after persistence`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        val payload = validPayload()
        every { message.payload } returns payload
        every { message.ack() } returns CompletableFuture.completedFuture(null)
        coEvery { repository.save(any()) } returns Unit

        consumer.consume(message)

        coVerify {
            repository.save(match { it.actorId == null && it.payload == payload && it.eventType == EVENT_TYPE })
        }
        verify(exactly = 1) { message.ack() }
    }

    @Test
    fun `disclosure commitment is accepted without local evidence details`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        val payload = validPayload("CONTEXT_DISCLOSURE_COMMITTED", "CONTEXT_DISCLOSURE")
        every { message.payload } returns payload
        every { message.ack() } returns CompletableFuture.completedFuture(null)
        coEvery { repository.save(any()) } returns Unit

        consumer.consume(message)

        coVerify {
            repository.save(
                match {
                    it.eventType == "CONTEXT_DISCLOSURE_COMMITTED" &&
                        it.aggregateType == "CONTEXT_DISCLOSURE"
                },
            )
        }
        verify(exactly = 1) { message.ack() }
    }

    @Test
    fun `disclosure type cannot be paired with read audit aggregate`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        every { message.payload } returns validPayload("CONTEXT_DISCLOSURE_COMMITTED", "CONTEXT_READ_AUDIT")

        assertThatThrownBy { runBlocking { consumer.consume(message) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { message.ack() }
    }

    @Test
    fun `disclosure commitment rejects evidence references on the transport`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        every { message.payload } returns validPayload("CONTEXT_DISCLOSURE_COMMITTED", "CONTEXT_DISCLOSURE")
            .replaceFirst("{", "{\"evidenceRefs\":[\"synthetic-ref\"],")

        assertThatThrownBy { runBlocking { consumer.consume(message) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { message.ack() }
    }

    @Test
    fun `rejects an identity field before storage or acknowledgment`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        every { message.payload } returns validPayload().replaceFirst("{", "{\"principalId\":\"person-1\",")

        assertThatThrownBy { runBlocking { consumer.consume(message) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { message.ack() }
    }

    @Test
    fun `store failure is never acknowledged`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        every { message.payload } returns validPayload()
        coEvery { repository.save(any()) } throws IllegalStateException("store unavailable")

        assertThatThrownBy { runBlocking { consumer.consume(message) } }
            .isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { message.ack() }
    }

    private fun validPayload(eventType: String = EVENT_TYPE, aggregateType: String = "CONTEXT_READ_AUDIT"): String {
        val id = UUID.randomUUID()
        return jacksonObjectMapper().writeValueAsString(
            mapOf(
                "schemaVersion" to 1,
                "eventId" to id.toString(),
                "eventType" to eventType,
                "aggregateType" to aggregateType,
                "aggregateId" to id.toString(),
                "sourceService" to "context-service",
                "occurredAt" to "2026-09-18T11:59:00Z",
                "commitment" to "a".repeat(64),
            ),
        )
    }

    companion object {
        private const val EVENT_TYPE = "CONTEXT_READ_AUDIT_COMMITTED"
    }
}
