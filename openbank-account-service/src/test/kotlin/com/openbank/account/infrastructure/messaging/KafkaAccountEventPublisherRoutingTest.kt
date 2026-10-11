// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.messaging

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.domain.event.AccountClosedEvent
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.event.AccountStatusChangedEvent
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.Record
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class KafkaAccountEventPublisherRoutingTest {

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val created = mockk<Emitter<Record<String, String>>>()
    private val status = mockk<Emitter<Record<String, String>>>()
    private val publisher = KafkaAccountEventPublisher(mapper, created, status)
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private val accountId = UUID.randomUUID()
    private val key = accountId.toString()

    @Test
    fun `only AccountCreated reaches the created emitter`(): Unit = runBlocking {
        val sent = slot<Record<String, String>>()
        every { created.send(capture(sent)) } returns CompletableFuture.completedFuture(null)

        publisher.publish(CREATED_TOPIC, key, createdEvent())

        assertThat(sent.captured.key()).isEqualTo(key)
        assertThat(mapper.readTree(sent.captured.value())["eventType"].asText()).isEqualTo("AccountCreated")
        verify(exactly = 1) { created.send(any()) }
        verify(exactly = 0) { status.send(any()) }
    }

    @Test
    fun `status change and closure reach only the status emitter`(): Unit = runBlocking {
        val sent = mutableListOf<Record<String, String>>()
        every { status.send(capture(sent)) } returns CompletableFuture.completedFuture(null)

        publisher.publish(STATUS_TOPIC, key, statusEvent())
        publisher.publish(STATUS_TOPIC, key, closedEvent())

        assertThat(sent.map { mapper.readTree(it.value())["eventType"].asText() })
            .containsExactly("AccountStatusChanged", "AccountClosed")
        assertThat(sent.map { it.key() }).containsExactly(key, key)
        verify(exactly = 0) { created.send(any()) }
        verify(exactly = 2) { status.send(any()) }
    }

    @Test
    fun `mismatched topic and unknown type fail before either emitter`() {
        assertThatThrownBy { runBlocking { publisher.publish(CREATED_TOPIC, key, statusEvent()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { publisher.publish(STATUS_TOPIC, key, createdEvent()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { publisher.publish(CREATED_TOPIC, key, Any()) } }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { created.send(any()) }
        verify(exactly = 0) { status.send(any()) }
    }

    private fun createdEvent() = AccountCreatedEvent(
        aggregateId = accountId,
        version = 0,
        accountNumber = "CZ6508000000192000145399",
        accountType = AccountType.CURRENT,
        partyId = UUID.randomUUID(),
        productId = UUID.randomUUID(),
        currency = "CZK",
        occurredAt = now,
    )

    private fun statusEvent() = AccountStatusChangedEvent(
        aggregateId = accountId,
        version = 1,
        previousStatus = AccountStatus.PENDING_ACTIVATION,
        newStatus = AccountStatus.ACTIVE,
        reason = "screening cleared",
        occurredAt = now,
    )

    private fun closedEvent() = AccountClosedEvent(
        aggregateId = accountId,
        version = 2,
        reason = "customer request",
        occurredAt = now,
    )

    companion object {
        private const val CREATED_TOPIC = "openbank.accounts.account.created"
        private const val STATUS_TOPIC = "openbank.accounts.account.status-changed"
    }
}
