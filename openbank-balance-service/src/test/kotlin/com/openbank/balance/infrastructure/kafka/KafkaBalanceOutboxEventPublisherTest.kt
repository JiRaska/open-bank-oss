// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.kafka

import com.openbank.balance.application.usecase.LowBalanceAlertService
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KafkaBalanceOutboxEventPublisherTest {
    private val balanceEmitter = mockk<MutinyEmitter<String>>()
    private val notificationEmitter = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaBalanceOutboxEventPublisher(balanceEmitter, notificationEmitter)

    @Test
    fun `low balance intent uses notification topic`() {
        every { notificationEmitter.sendMessage(any<Message<String>>()) } returns Uni.createFrom().voidItem()

        runBlocking { publisher.publish(entry(LowBalanceAlertService.LOW_BALANCE_REQUEST)) }

        verify(exactly = 1) { notificationEmitter.sendMessage(any<Message<String>>()) }
        verify(exactly = 0) { balanceEmitter.sendMessage(any<Message<String>>()) }
    }

    @Test
    fun `ordinary balance event stays on balance topic`() {
        every { balanceEmitter.sendMessage(any<Message<String>>()) } returns Uni.createFrom().voidItem()

        runBlocking { publisher.publish(entry("BALANCE_UPDATED")) }

        verify(exactly = 1) { balanceEmitter.sendMessage(any<Message<String>>()) }
        verify(exactly = 0) { notificationEmitter.sendMessage(any<Message<String>>()) }
    }

    private fun entry(type: String): OutboxEntry {
        val now = Instant.now()
        return OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = type,
            payload = "{}",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = now,
            updatedAt = now,
            sentAt = null,
            lastError = null,
        )
    }
}
