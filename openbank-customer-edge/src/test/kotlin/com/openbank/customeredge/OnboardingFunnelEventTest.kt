// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.onboarding.OnboardingFunnelPublisher
import com.openbank.customeredge.infrastructure.rest.OnboardingResource
import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OnboardingResource.recordFunnelEvent] (ADR-0069 Phase 2 funnel telemetry).
 *
 * Contract: a well-formed event is a best-effort 202 that emits exactly once; anything malformed
 * (bad session id, unknown step/action, oversized body) is rejected with NO emission, because this
 * is an anonymous write that ultimately lands in a 10-year store. The publisher is mocked (it owns
 * the Kafka channel + Prometheus counter; its emission is covered separately).
 */
class OnboardingFunnelEventTest {

    private val publisher = mockk<OnboardingFunnelPublisher>(relaxed = true)

    private fun resource(): OnboardingResource {
        val r = OnboardingResource(
            upstream = mockk(relaxed = true),
            enrollmentTicketService = mockk<EnrollmentTicketService>(relaxed = true),
            funnelPublisher = publisher,
        )
        r.jsonMapper = ObjectMapper()
        return r
    }

    private val validSession = "11111111-2222-4333-8444-555555555555"

    @Test
    fun `well-formed event is accepted with 202 and emitted once`() {
        val body = """{"sessionId":"$validSession","step":"SIGN","action":"SIGN_FAIL","reason":"409"}"""

        val resp = resource().recordFunnelEvent(body)

        assertThat(resp.status).isEqualTo(202)
        verify(exactly = 1) {
            publisher.emit(validSession, "SIGN", "SIGN_FAIL", match { it["reason"] == "409" })
        }
    }

    @Test
    fun `lower-case step and action are upper-cased before matching`() {
        val body = """{"sessionId":"$validSession","step":"agreement","action":"step_completed"}"""

        val resp = resource().recordFunnelEvent(body)

        assertThat(resp.status).isEqualTo(202)
        verify(exactly = 1) { publisher.emit(validSession, "AGREEMENT", "STEP_COMPLETED", any()) }
    }

    @Test
    fun `unknown step is rejected with 400 and never emitted`() {
        val body = """{"sessionId":"$validSession","step":"BOGUS","action":"STEP_VIEWED"}"""

        val resp = resource().recordFunnelEvent(body)

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any(), any(), any(), any()) }
    }

    @Test
    fun `unknown action is rejected with 400 and never emitted`() {
        val body = """{"sessionId":"$validSession","step":"SIGN","action":"HACKED"}"""

        val resp = resource().recordFunnelEvent(body)

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any(), any(), any(), any()) }
    }

    @Test
    fun `non-uuid sessionId is rejected with 400 and never emitted`() {
        val body = """{"sessionId":"not-a-uuid","step":"SIGN","action":"SIGN_ATTEMPT"}"""

        val resp = resource().recordFunnelEvent(body)

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { publisher.emit(any(), any(), any(), any()) }
    }

    @Test
    fun `oversized body is rejected with 413 and never emitted`() {
        val body = "{\"sessionId\":\"$validSession\",\"pad\":\"" + "x".repeat(1_100) + "\"}"

        val resp = resource().recordFunnelEvent(body)

        assertThat(resp.status).isEqualTo(413)
        verify(exactly = 0) { publisher.emit(any(), any(), any(), any()) }
    }

    @Test
    fun `only the allow-listed attributes are forwarded`() {
        val body = """
            {"sessionId":"$validSession","step":"IDENTITY","action":"KYC_METHOD_CHOSEN",
             "kycMethod":"BANKID","platform":"ios","appVersion":"0.3.0","evil":"drop-me"}
        """.trimIndent()

        resource().recordFunnelEvent(body)

        verify(exactly = 1) {
            publisher.emit(
                validSession,
                "IDENTITY",
                "KYC_METHOD_CHOSEN",
                match {
                    it["kycMethod"] == "BANKID" &&
                        it["platform"] == "ios" &&
                        it["appVersion"] == "0.3.0" &&
                        !it.containsKey("evil")
                },
            )
        }
    }
}
