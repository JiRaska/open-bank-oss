// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.ScreeningTriggerService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Named so detekt's TooGenericExceptionThrown does not have to be suppressed at the throw site. */
private class TriggerTableFailure : RuntimeException("connection refused")

class SanctionsListChangedConsumerTest {

    private val screeningTriggerService = mockk<ScreeningTriggerService>()
    private lateinit var consumer: SanctionsListChangedConsumer

    @BeforeEach
    fun setUp() {
        consumer = SanctionsListChangedConsumer().also {
            it.screeningTriggerService = screeningTriggerService
            it.objectMapper = ObjectMapper()
        }
    }

    private fun enable() {
        consumer.enabled = "true"
        consumer.candidateLimit = "500"
    }

    @Test
    fun `SANCTIONS_LIST_CHANGE_STORM opens no cases and touches no dependency`(): Unit = runBlocking {
        enable()
        consumer.consume(
            """{"eventType":"SANCTIONS_LIST_CHANGE_STORM","listType":"EU_CONSOLIDATED","reason":"CHANGE_SHARE"}""",
        )

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `unknown event types on the topic are ignored`(): Unit = runBlocking {
        enable()
        consumer.consume("""{"eventType":"SANCTIONS_ENTRY_ADDED"}""")

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `malformed payload is acked without throwing`(): Unit = runBlocking {
        enable()
        consumer.consume("not json")

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `the trigger loop stays off unless enabled, acking real diffs untouched`(): Unit = runBlocking {
        consumer.enabled = "false"
        consumer.candidateLimit = "500"

        consumer.consume(changedEvent())

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `a valid diff is parsed into a ListChange and handed to the service`(): Unit = runBlocking {
        enable()
        val publicationId = UUID.randomUUID()
        coEvery {
            screeningTriggerService.handleListChange(any(), 500)
        } returns ScreeningTriggerService.HandleResult(candidates = 3, opened = 3, failed = 0, duplicates = 0)

        consumer.consume(changedEvent(publicationId))

        val captured = slot<ScreeningTriggerService.ListChange>()
        coVerify(exactly = 1) { screeningTriggerService.handleListChange(capture(captured), 500) }
        assertThat(captured.captured.publicationId).isEqualTo(publicationId)
        assertThat(captured.captured.chunkIndex).isEqualTo(0)
        assertThat(captured.captured.listType).isEqualTo("EU_CONSOLIDATED")
        assertThat(captured.captured.changedExternalIds).containsExactly("ext-1", "ext-2")
        assertThat(captured.captured.deactivatedExternalIds).containsExactly("ext-9")
    }

    @Test
    fun `a diff without a valid publicationId is skipped`(): Unit = runBlocking {
        enable()
        consumer.consume("""{"eventType":"SANCTIONS_LIST_CHANGED","publicationId":"not-a-uuid"}""")

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `an infrastructure failure is retried then rethrown so the connector dead-letters`(): Unit = runBlocking {
        enable()
        coEvery { screeningTriggerService.handleListChange(any(), any()) } throws TriggerTableFailure()

        assertThrows<TriggerTableFailure> { consumer.consume(changedEvent()) }

        coVerify(exactly = 3) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `a transient failure is retried and the diff is handled without dead-lettering`(): Unit = runBlocking {
        enable()
        var calls = 0
        coEvery { screeningTriggerService.handleListChange(any(), any()) } answers {
            calls++
            if (calls == 1) throw TriggerTableFailure()
            ScreeningTriggerService.HandleResult(candidates = 1, opened = 1, failed = 0, duplicates = 0)
        }

        consumer.consume(changedEvent())

        coVerify(exactly = 2) { screeningTriggerService.handleListChange(any(), any()) }
    }

    private fun changedEvent(publicationId: UUID = UUID.randomUUID()) = """
        {"eventType":"SANCTIONS_LIST_CHANGED","listType":"EU_CONSOLIDATED",
         "changedExternalIds":["ext-1","ext-2"],"deactivatedExternalIds":["ext-9"],
         "changeCount":3,"publicationId":"$publicationId","chunkIndex":0,"chunkCount":1}
    """.trimIndent()
}
