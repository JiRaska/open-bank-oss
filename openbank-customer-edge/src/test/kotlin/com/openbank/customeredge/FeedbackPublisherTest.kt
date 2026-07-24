// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.feedback.FeedbackPublisher
import com.openbank.customeredge.infrastructure.feedback.FeedbackScreenshotStore
import com.openbank.customeredge.infrastructure.feedback.FeedbackSubmission
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.reactive.messaging.kafka.Record
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for [FeedbackPublisher] (ADR-0192).
 *
 * The contract that matters downstream: the event is the SAME analytics envelope analytics-sink
 * already ingests generically (which is why feedback needs no new consumer), it is keyed on the
 * reference, and it carries the screenshot's object-store KEY — never the image bytes. Emission
 * is best-effort: a broker failure must not propagate to the customer who pressed send.
 */
class FeedbackPublisherTest {

    private val emitter = mockk<Emitter<Record<String, String>>>()
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-07-24T10:15:30Z"), ZoneOffset.UTC)
    private val mapper = ObjectMapper()

    private fun publisher() = FeedbackPublisher(mapper, emitter, clock, registry)

    private fun submission(
        screenshotKey: String? = "customer-edge/feedback/${UUID.randomUUID()}.png",
        status: String = FeedbackScreenshotStore.STATUS_STORED,
    ) = FeedbackSubmission(
        reference = "FB-3F9A21C0B7D4",
        partyId = "11111111-2222-4333-8444-555555555555",
        screenId = "payments/new",
        category = "BUG",
        comment = "Confirm button hides behind the keyboard",
        platform = "ios",
        appVersion = "0.4.0",
        screenshotKey = screenshotKey,
        screenshotBytes = 40,
        screenshotStatus = status,
    )

    @Test
    fun `emits the analytics envelope keyed on the reference, carrying the key and not the bytes`() {
        val record = slot<Record<String, String>>()
        every { emitter.send(capture(record)) } returns CompletableFuture.completedFuture(null)
        val sent = submission()

        publisher().emit(sent)

        assertThat(record.captured.key()).isEqualTo(sent.reference)
        val node = mapper.readTree(record.captured.value())
        assertThat(node["eventType"].asText()).isEqualTo("feedback.submitted")
        assertThat(node["aggregateType"].asText()).isEqualTo("SCREEN_FEEDBACK")
        assertThat(node["aggregateId"].asText()).isEqualTo(sent.reference)
        assertThat(node["sourceService"].asText()).isEqualTo("customer-edge")
        assertThat(node["schemaVersion"].asInt()).isEqualTo(1)
        assertThat(node["occurredAt"].asText()).isEqualTo("2026-07-24T10:15:30Z")

        val payload = node["payload"]
        assertThat(payload["partyId"].asText()).isEqualTo(sent.partyId)
        assertThat(payload["screenId"].asText()).isEqualTo("payments/new")
        assertThat(payload["category"].asText()).isEqualTo("BUG")
        assertThat(payload["comment"].asText()).isEqualTo(sent.comment)
        assertThat(payload["screenshotKey"].asText()).isEqualTo(sent.screenshotKey)
        assertThat(payload["screenshotStatus"].asText()).isEqualTo("STORED")
        // The whole point: no base64, no bytes, anywhere in the event.
        assertThat(record.captured.value()).doesNotContain("screenshotPngBase64")
    }

    @Test
    fun `a text-only submission carries no screenshot key`() {
        val record = slot<Record<String, String>>()
        every { emitter.send(capture(record)) } returns CompletableFuture.completedFuture(null)

        publisher().emit(submission(screenshotKey = null, status = FeedbackScreenshotStore.STATUS_NONE))

        val payload = mapper.readTree(record.captured.value())["payload"]
        assertThat(payload.has("screenshotKey")).isFalse()
        assertThat(payload["screenshotStatus"].asText()).isEqualTo("NONE")
    }

    @Test
    fun `submissions are counted by category and screenshot status`() {
        every { emitter.send(any<Record<String, String>>()) } returns CompletableFuture.completedFuture(null)

        publisher().emit(submission())

        val counter = registry.find("feedback_submissions")
            .tag("category", "BUG")
            .tag("screenshot_status", "STORED")
            .counter()
        assertThat(counter?.count()).isEqualTo(1.0)
    }

    @Test
    fun `a broker failure is counted, not thrown at the caller`() {
        every { emitter.send(any<Record<String, String>>()) } throws IllegalStateException("broker down")

        publisher().emit(submission())

        assertThat(registry.find("feedback_emit_failures").counter()?.count()).isEqualTo(1.0)
    }
}
