// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.feedback.FeedbackPublisher
import com.openbank.customeredge.infrastructure.feedback.FeedbackScreenshotStore
import com.openbank.customeredge.infrastructure.feedback.FeedbackSubmission
import com.openbank.customeredge.infrastructure.ratelimit.RateLimiter
import com.openbank.customeredge.infrastructure.rest.FeedbackResource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * Unit tests for [FeedbackResource.submit] (ADR-0192 in-app screen feedback).
 *
 * Contract: a well-formed submission is a 202 carrying a support-quotable reference and emits
 * exactly one metadata event; anything malformed, oversized, non-PNG or over quota is rejected
 * with NO emission and NO object-store write — this endpoint is an authenticated write into a
 * 10-year store AND an upload channel into a bucket, so both sides must fail closed.
 *
 * The publisher, screenshot store and rate limiter are mocked (they own Kafka, S3 and Valkey
 * respectively); what is under test here is the validation and the token-derived party id.
 */
class FeedbackSubmissionTest {

    private val publisher = mockk<FeedbackPublisher>(relaxed = true)
    private val screenshotStore = mockk<FeedbackScreenshotStore>()
    private val rateLimiter = mockk<RateLimiter>()
    private val caller = UUID.randomUUID()

    private fun resource(allowed: Boolean = true): FeedbackResource {
        every { rateLimiter.isWithinWindow(any(), any(), any(), any(), any()) } returns allowed
        every { screenshotStore.store(any(), any(), any()) } answers {
            FeedbackScreenshotStore.Result("customer-edge/feedback/${firstArg<UUID>()}.png", "STORED")
        }
        return FeedbackResource(screenshotStore, publisher, rateLimiter).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns caller.toString()
                every { subject } returns caller.toString()
            }
            objectMapper = ObjectMapper()
        }
    }

    /** Smallest byte sequence that passes the PNG signature check. */
    private fun pngBase64(payloadSize: Int = 32): String {
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return Base64.getEncoder().encodeToString(magic + ByteArray(payloadSize))
    }

    private fun body(
        screenId: String = "payments/new",
        category: String = "BUG",
        comment: String = "The confirm button is hidden behind the keyboard",
        extra: String = "",
    ) = """{"screenId":"$screenId","category":"$category","comment":"$comment"$extra}"""

    @Test
    fun `well-formed text-only feedback is accepted with 202 and emitted once`() {
        val resp = resource().submit(body())

        assertThat(resp.status).isEqualTo(202)
        @Suppress("UNCHECKED_CAST")
        val entity = resp.entity as Map<String, String>
        assertThat(entity["reference"]).startsWith("FB-").hasSize(15)
        val emitted = slot<FeedbackSubmission>()
        verify(exactly = 1) { publisher.emit(capture(emitted)) }
        assertThat(emitted.captured.screenshotStatus).isEqualTo(FeedbackScreenshotStore.STATUS_NONE)
        assertThat(emitted.captured.screenshotKey).isNull()
        assertThat(emitted.captured.reference).isEqualTo(entity["reference"])
        verify(exactly = 0) { screenshotStore.store(any(), any(), any()) }
    }

    @Test
    fun `party id comes from the token and never from the body`() {
        val attacker = UUID.randomUUID()
        val resp = resource().submit(body(extra = ""","partyId":"$attacker""""))

        assertThat(resp.status).isEqualTo(202)
        val emitted = slot<FeedbackSubmission>()
        verify(exactly = 1) { publisher.emit(capture(emitted)) }
        assertThat(emitted.captured.partyId).isEqualTo(caller.toString())
    }

    @Test
    fun `a confirmed PNG screenshot is stored and only its key is emitted`() {
        val resp = resource().submit(body(extra = ""","screenshotPngBase64":"${pngBase64()}""""))

        assertThat(resp.status).isEqualTo(202)
        val emitted = slot<FeedbackSubmission>()
        verify(exactly = 1) { publisher.emit(capture(emitted)) }
        assertThat(emitted.captured.screenshotKey).startsWith("customer-edge/feedback/").endsWith(".png")
        assertThat(emitted.captured.screenshotStatus).isEqualTo(FeedbackScreenshotStore.STATUS_STORED)
        assertThat(emitted.captured.screenshotBytes).isEqualTo(40)
    }

    @Test
    fun `a failed screenshot write still delivers the comment, flagged`() {
        val r = resource()
        every { screenshotStore.store(any(), any(), any()) } returns
            FeedbackScreenshotStore.Result(null, FeedbackScreenshotStore.STATUS_STORE_FAILED)

        val resp = r.submit(body(extra = ""","screenshotPngBase64":"${pngBase64()}""""))

        assertThat(resp.status).isEqualTo(202)
        val emitted = slot<FeedbackSubmission>()
        verify(exactly = 1) { publisher.emit(capture(emitted)) }
        assertThat(emitted.captured.screenshotStatus).isEqualTo(FeedbackScreenshotStore.STATUS_STORE_FAILED)
        assertThat(emitted.captured.comment).isNotBlank()
    }

    @Test
    fun `unknown category is rejected with 400 and never emitted`() {
        val resp = resource().submit(body(category = "RANT"))

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `lower-case category is accepted and normalised`() {
        val resp = resource().submit(body(category = "idea"))

        assertThat(resp.status).isEqualTo(202)
        val emitted = slot<FeedbackSubmission>()
        verify(exactly = 1) { publisher.emit(capture(emitted)) }
        assertThat(emitted.captured.category).isEqualTo("IDEA")
    }

    @Test
    fun `screenId outside the route charset is rejected with 400`() {
        val resp = resource().submit(body(screenId = "payments new; DROP"))

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `missing screenId is rejected with 400`() {
        val resp = resource().submit("""{"category":"BUG","comment":"hi"}""")

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `an empty submission (no comment, no screenshot) is rejected with 400`() {
        val resp = resource().submit("""{"screenId":"home","category":"BUG"}""")

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `a non-PNG screenshot is rejected with 400 and never stored`() {
        val notPng = Base64.getEncoder().encodeToString("%PDF-1.7 not a screenshot".toByteArray())

        val resp = resource().submit(body(extra = ""","screenshotPngBase64":"$notPng""""))

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { screenshotStore.store(any(), any(), any()) }
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `a non-base64 screenshot is rejected with 400`() {
        val resp = resource().submit(body(extra = ""","screenshotPngBase64":"not base64 at all!""""))

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `a screenshot over 2 MB decoded is rejected with 413`() {
        val resp = resource().submit(body(extra = ""","screenshotPngBase64":"${pngBase64(2 * 1024 * 1024)}""""))

        assertThat(resp.status).isEqualTo(413)
        verify(exactly = 0) { screenshotStore.store(any(), any(), any()) }
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `an oversized body is rejected with 413 before it is parsed`() {
        val resp = resource().submit("x".repeat(3 * 1024 * 1024 + 1))

        assertThat(resp.status).isEqualTo(413)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `malformed JSON is rejected with 400`() {
        val resp = resource().submit("""{"screenId":""")

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `comment is truncated to the declared cap rather than rejected`() {
        val resp = resource().submit(body(comment = "a".repeat(2_500)))

        assertThat(resp.status).isEqualTo(202)
        val emitted = slot<FeedbackSubmission>()
        verify(exactly = 1) { publisher.emit(capture(emitted)) }
        assertThat(emitted.captured.comment).hasSize(2000)
    }

    @Test
    fun `over the hourly quota the submission is rejected with 429 and never stored`() {
        val resp = resource(allowed = false).submit(body(extra = ""","screenshotPngBase64":"${pngBase64()}""""))

        assertThat(resp.status).isEqualTo(429)
        assertThat(resp.getHeaderString("Retry-After")).isEqualTo("3600")
        verify(exactly = 0) { screenshotStore.store(any(), any(), any()) }
        verify(exactly = 0) { publisher.emit(any()) }
    }

    @Test
    fun `the feedback quota uses its own scope and window, not the global request budget`() {
        val r = resource()
        val scope = slot<String>()
        val window = slot<Long>()
        every { rateLimiter.isWithinWindow(capture(scope), any(), any(), capture(window), any()) } returns true

        r.submit(body())

        assertThat(scope.captured).isEqualTo("feedback-quota")
        assertThat(window.captured).isEqualTo(3600L)
    }
}
