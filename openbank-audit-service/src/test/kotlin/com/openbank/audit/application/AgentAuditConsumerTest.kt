// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AgentAuditConsumerTest {
    private val auditConsumer = mockk<AuditConsumer>()
    private val consumer = AgentAuditConsumer().also { it.auditConsumer = auditConsumer }

    @Test
    fun `acknowledges only after durable persistence`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        val payload = "{\"eventId\":\"4d8b95a8-5cf9-4b56-96a1-3eaef222e714\"}"
        every { message.payload } returns payload
        coEvery { auditConsumer.persist(any(), any()) } returns Unit
        every { message.ack() } returns CompletableFuture.completedFuture(null)

        consumer.consume(message)

        coVerify { auditConsumer.persist(any(), any()) }
        verify { message.ack() }
    }

    @Test
    fun `does not acknowledge a failed audit-store write`(): Unit = runBlocking {
        val message = mockk<Message<String>>()
        every { message.payload } returns "broken"
        val failure = IllegalStateException("store unavailable")
        coEvery { auditConsumer.persist(any(), any()) } throws failure
        every { message.nack(failure) } returns CompletableFuture.completedFuture(null)

        consumer.consume(message)
        verify(exactly = 1) { message.nack(failure) }
        verify(exactly = 0) { message.ack() }
    }
}
