// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.messaging

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class KafkaAccountOutboxEventPublisherRoutingTest {

    @Test
    fun `withdrawal approval uses the dedicated fixed channel with its outbox identity`(): Unit = runBlocking {
        val emitter = mockk<MutinyEmitter<String>>()
        val sent = slot<Message<String>>()
        every { emitter.sendMessage(capture(sent)) } returns Uni.createFrom().voidItem()
        val entry = OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "SavingsWithdrawalApproved",
            payload = "{\"eventType\":\"SavingsWithdrawalApproved\"}",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            sentAt = null,
            lastError = null,
        )

        KafkaAccountOutboxEventPublisher(emitter).publish(entry)

        verify(exactly = 1) { emitter.sendMessage(any<Message<String>>()) }
        assertThat(sent.captured.payload).isEqualTo(entry.payload)
        val metadata = sent.captured.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
        assertThat(metadata.key).isEqualTo(entry.aggregateId.toString())
        assertThat(metadata.headers.lastHeader("ce-type").value().toString(Charsets.UTF_8))
            .isEqualTo("SavingsWithdrawalApproved")
        assertThat(metadata.headers.lastHeader("ce-id").value().toString(Charsets.UTF_8))
            .isEqualTo(entry.eventId.toString())
        assertThat(metadata.headers.lastHeader("idempotency-key").value().toString(Charsets.UTF_8))
            .isEqualTo(entry.eventId.toString())

        val constructorChannel = KafkaAccountOutboxEventPublisher::class.java.declaredConstructors
            .single().parameters.single().getAnnotation(Channel::class.java)
        assertThat(constructorChannel.value).isEqualTo("account-outbox-out")
        val config = File("src/main/resources/application.yaml").readText()
        val channelBlock = config.substringAfter("      account-outbox-out:").substringBefore("      # Customer-facing")
        assertThat(channelBlock).contains("topic: openbank.accounts.savings-withdrawal.approved")
    }

    @Test
    fun `unknown outbox type cannot enter the withdrawal topic`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val entry = OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "UnexpectedAccountEvent",
            payload = "{}",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            sentAt = null,
            lastError = null,
        )

        assertThatThrownBy { runBlocking { KafkaAccountOutboxEventPublisher(emitter).publish(entry) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { emitter.sendMessage(any<Message<String>>()) }
    }
}
