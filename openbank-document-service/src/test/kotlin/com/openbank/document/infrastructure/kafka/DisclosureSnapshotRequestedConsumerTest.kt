// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.document.application.port.`in`.DisclosureSnapshotUseCase
import com.openbank.document.domain.model.DisclosureSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DisclosureSnapshotRequestedConsumerTest {
    private val useCase: DisclosureSnapshotUseCase = mockk()
    private val emitter: MutinyEmitter<String> = mockk()
    private val emitted = slot<Message<String>>()
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val consumer = DisclosureSnapshotRequestedConsumer(useCase, mapper, emitter)

    @Test
    fun `valid command emits content-addressed ready event`(): Unit = runBlocking {
        every { emitter.sendMessage(capture(emitted)) } returns Uni.createFrom().voidItem()
        coEvery { useCase.issue(any()) } returns snapshot()

        consumer.consume(command())

        val event = mapper.readTree(emitted.captured.payload)
        assertThat(event.path("eventType").asText()).isEqualTo("DisclosureSnapshotReady")
        assertThat(event.path("requestId").asText()).isEqualTo(REQUEST_ID.toString())
        assertThat(event.path("snapshotId").asText()).isEqualTo(SNAPSHOT_ID.toString())
        assertThat(event.path("sha256").asText()).isEqualTo("b".repeat(64))
        assertThat(emitted.captured.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().key)
            .isEqualTo(REQUEST_ID.toString())
    }

    @Test
    fun `ineligible source emits bounded rejection without leaking a reason string`(): Unit = runBlocking {
        every { emitter.sendMessage(capture(emitted)) } returns Uni.createFrom().voidItem()
        coEvery { useCase.issue(any()) } throws IllegalArgumentException("belongs to another party")

        consumer.consume(command())

        val event = mapper.readTree(emitted.captured.payload)
        assertThat(event.path("eventType").asText()).isEqualTo("DisclosureSnapshotRejected")
        assertThat(event.path("reason").asText()).isEqualTo("SOURCE_NOT_ELIGIBLE")
        assertThat(emitted.captured.payload).doesNotContain("another party")
    }

    @Test
    fun `unrelated delegation event is ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"DelegationActivated"}""")

        coVerify(exactly = 0) { useCase.issue(any()) }
        coVerify(exactly = 0) { emitter.sendMessage(any()) }
    }

    private fun command() =
        """{"eventType":"DisclosureSnapshotRequested","requestId":"$REQUEST_ID","sourceDocumentId":"$SOURCE_ID","expectedPartyRef":"$PARTY"}"""

    private fun snapshot() = DisclosureSnapshot(
        SNAPSHOT_ID,
        REQUEST_ID,
        SOURCE_ID,
        PARTY,
        "a".repeat(64),
        "b".repeat(64),
        "documents/disclosures/$SNAPSHOT_ID/${"b".repeat(64)}",
        "application/pdf",
        42,
        Instant.parse("2026-09-09T10:00:00Z"),
    )

    private companion object {
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val SOURCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val SNAPSHOT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000043")
        const val PARTY = "00000000-0000-0000-0000-000000000044"
    }
}
