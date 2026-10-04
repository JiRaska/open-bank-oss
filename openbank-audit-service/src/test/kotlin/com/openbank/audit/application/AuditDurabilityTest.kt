// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AuditDurabilityTest {
    private val repository = mockk<AuditRepository>()
    private val consumer = AuditConsumer().also {
        it.repo = repository
        it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
        it.clock = Clock.systemUTC()
        it.meterRegistry = SimpleMeterRegistry()
    }
    private val payload = """{"eventType":"AccountCreated","accountId":"11111111-1111-1111-1111-111111111111"}"""

    @Test
    fun `failed persistence nacks instead of acknowledging the lost audit row`(): Unit = runBlocking {
        val failure = IllegalStateException("store unavailable")
        coEvery { repository.save(any()) } throws failure
        val message = message(payload)
        consumer.consume(message)
        verify(exactly = 0) { message.ack() }
        verify(exactly = 1) { message.nack(failure) }
        assertThat(consumer.meterRegistry.counter("openbank.audit.ingest.failures").count()).isEqualTo(1.0)
    }

    @Test
    fun `malformed audit record is nacked for configured dead letter handling`(): Unit = runBlocking {
        val message = message("not-json")
        consumer.consume(message)
        verify(exactly = 0) { message.ack() }
        verify(exactly = 1) { message.nack(any()) }
    }

    @Test
    fun `broker redelivery without producer id preserves the audit entry identity`(): Unit = runBlocking {
        val entries = mutableListOf<AuditEntry>()
        coEvery { repository.save(capture(entries)) } returns Unit
        consumer.consume(message(payload, offset = 7))
        consumer.consume(message(payload, offset = 7))
        consumer.consume(message(payload, offset = 8))
        assertThat(entries[0].id).isEqualTo(entries[1].id)
        assertThat(entries[0].id).isNotEqualTo(entries[2].id)
    }

    @Test
    fun `producer identity remains authoritative across broker positions`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val entries = mutableListOf<AuditEntry>()
        coEvery { repository.save(capture(entries)) } returns Unit
        val withId = payload.dropLast(1) + ",\"eventId\":\"$id\"}"
        consumer.consume(message(withId, offset = 7))
        consumer.consume(message(withId, offset = 8))
        assertThat(entries.map { it.id }).containsExactly(id, id)
    }

    @Test
    fun `failed nack propagates and never acknowledges the unstored record`() {
        coEvery { repository.save(any()) } throws IllegalStateException("store unavailable")
        val message = message(payload)
        every { message.nack(any()) } returns CompletableFuture.failedFuture(IllegalStateException("DLQ unavailable"))
        assertThatThrownBy { runBlocking { consumer.consume(message) } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("DLQ unavailable")
        verify(exactly = 0) { message.ack() }
    }

    @Test
    fun `lost acknowledgement propagates without nacking an already stored record`() {
        coEvery { repository.save(any()) } returns Unit
        val message = message(payload)
        every { message.ack() } returns CompletableFuture.failedFuture(IllegalStateException("acknowledgement lost"))
        assertThatThrownBy { runBlocking { consumer.consume(message) } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("acknowledgement lost")
        verify(exactly = 0) { message.nack(any()) }
    }

    @Test
    fun `JSON string wrapper cannot be stored as an unattributed substitute for the event`(): Unit = runBlocking {
        coEvery { repository.save(any()) } returns Unit
        val wrapped = jacksonObjectMapper().writeValueAsString(payload)
        val message = message(wrapped)
        consumer.consume(message)
        verify(exactly = 0) { message.ack() }
        verify(exactly = 1) { message.nack(any()) }
    }

    private fun message(body: String, offset: Long = 0): Message<String> {
        val message = mockk<Message<String>>()
        val record = ConsumerRecord("openbank.party.events", 0, offset, null as String?, body)
        val metadata = IncomingKafkaRecordMetadata(record, "audit-events-in")
        every { message.payload } returns body
        every { message.getMetadata(IncomingKafkaRecordMetadata::class.java) } returns Optional.of(metadata)
        every { message.ack() } returns CompletableFuture.completedFuture(null)
        every { message.nack(any()) } returns CompletableFuture.completedFuture(null)
        return message
    }
}
