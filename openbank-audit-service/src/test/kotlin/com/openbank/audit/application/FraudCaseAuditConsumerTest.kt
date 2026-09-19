// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class FraudCaseAuditConsumerTest {
    private val audit = mockk<AuditConsumer>()
    private val subject = FraudCaseAuditConsumer(ObjectMapper(), audit)

    @Test
    fun `valid minimized event persists before ack`(): Unit = runBlocking {
        val payload = validPayload()
        coEvery { audit.persist(payload, any()) } returns Unit
        val message = mockk<Message<String>>()
        every { message.payload } returns payload
        every { message.ack() } returns CompletableFuture.completedFuture(null)

        subject.consume(message)

        coVerify(exactly = 1) { audit.persist(payload, any()) }
        verify(exactly = 1) { message.ack() }
    }

    @Test
    fun `store failure leaves the record unacknowledged`(): Unit = runBlocking {
        val payload = validPayload()
        coEvery { audit.persist(payload, any()) } throws IllegalStateException("database unavailable")
        val message = mockk<Message<String>>()
        every { message.payload } returns payload

        assertThatThrownBy { runBlocking { subject.consume(message) } }
            .isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { message.ack() }
    }

    @Test
    fun `unexpected identifiers and malformed events are rejected`() {
        val valid = validPayload()
        for (payload in listOf(
            valid.dropLast(1) + ",\"accountId\":\"${UUID.randomUUID()}\"}",
            valid.replace("fraud.case_opened.audit", "fraud.case_opened"),
            valid.replace("fraud-service", "unknown"),
            valid.replace("\"revision\":1", "\"revision\":0"),
            valid.replace("\"revision\":1", "\"revision\":\"1\""),
            valid.replace("\"actorId\":\"analyst\"", "\"actorId\":null"),
        )) {
            assertThatThrownBy { subject.validate(payload) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun validPayload(): String =
        """{"eventId":"${UUID.randomUUID()}","eventType":"fraud.case_opened.audit", """ +
            """"aggregateId":"${UUID.randomUUID()}","actorId":"analyst","revision":1,""" +
            """"occurredAt":"2026-09-18T00:00:00Z","aggregateType":"FRAUD_CASE", """ +
            """"sourceService":"fraud-service"}"""
}
