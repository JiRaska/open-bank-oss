// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.OnboardingResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for /onboarding/start input validation (issue #628 anti-abuse hardening).
 * Validates that the endpoint rejects malformed/missing/invalid input before forwarding
 * to party-service, preventing injection of arbitrary payloads via string-based checks.
 */
class OnboardingStartValidationTest {

    private fun resource(upstream: UpstreamClient = mockk()): OnboardingResource = OnboardingResource(
        upstream,
        mockk<EnrollmentTicketService>(relaxed = true).apply {
            every { issue(any()) } returns "fake-enrollment-ticket"
        },
        mockk(relaxed = true),
    ).apply {
        jsonMapper = ObjectMapper()
        partyServiceUrl = "http://party"
    }

    private val validBody = """{"partyType":"INDIVIDUAL","legalName":"Jana Nováková"}"""

    private fun partyCreated(id: String = "11111111-1111-1111-1111-111111111111") =
        Response.status(201).entity("""{"id":"$id","status":"PENDING_KYC"}""").build()

    // ---- happy path ---------------------------------------------------------

    @Test
    fun `valid request is forwarded to party-service and returns 201`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.postAnonymous(any(), any()) } returns partyCreated()

        val resp = resource(upstream).startOnboarding(validBody)

        assertThat(resp.status).isEqualTo(201)
        assertThat(resp.entity as String).contains("PENDING_ACTIVATION")
        verify(exactly = 1) { upstream.postAnonymous(any(), any()) }
    }

    // ---- missing fields -----------------------------------------------------

    @Test
    fun `missing partyType returns 400`() {
        val resp = resource().startOnboarding("""{"legalName":"Jan Novák"}""")
        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { mockk<UpstreamClient>().postAnonymous(any(), any()) }
    }

    @Test
    fun `missing legalName returns 400`() {
        val resp = resource().startOnboarding("""{"partyType":"INDIVIDUAL"}""")
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `blank legalName returns 400`() {
        val resp = resource().startOnboarding("""{"partyType":"INDIVIDUAL","legalName":"   "}""")
        assertThat(resp.status).isEqualTo(400)
    }

    // ---- invalid enum -------------------------------------------------------

    @Test
    fun `unknown partyType returns 400`() {
        val resp = resource().startOnboarding("""{"partyType":"HACKER","legalName":"Injected"}""")
        assertThat(resp.status).isEqualTo(400)
        assertThat(resp.entity as String).contains("INDIVIDUAL")
    }

    @Test
    fun `INDIVIDUAL is valid`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.postAnonymous(any(), any()) } returns partyCreated()
        val resp = resource(upstream).startOnboarding("""{"partyType":"INDIVIDUAL","legalName":"x"}""")
        assertThat(resp.status).isEqualTo(201)
    }

    @Test
    fun `LEGAL_ENTITY is valid`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.postAnonymous(any(), any()) } returns partyCreated()
        val resp = resource(upstream).startOnboarding("""{"partyType":"LEGAL_ENTITY","legalName":"x"}""")
        assertThat(resp.status).isEqualTo(201)
    }

    @Test
    fun `SOLE_TRADER is valid`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.postAnonymous(any(), any()) } returns partyCreated()
        val resp = resource(upstream).startOnboarding("""{"partyType":"SOLE_TRADER","legalName":"x"}""")
        assertThat(resp.status).isEqualTo(201)
    }

    // ---- payload size -------------------------------------------------------

    @Test
    fun `body over 4 KB returns 413`() {
        val huge = """{"partyType":"INDIVIDUAL","legalName":"${"x".repeat(5000)}"}"""
        val resp = resource().startOnboarding(huge)
        assertThat(resp.status).isEqualTo(413)
    }

    // ---- JSON injection guard -----------------------------------------------

    @Test
    fun `body containing legalName as substring but missing as JSON key returns 400`() {
        // Old string.contains("legalName") check would pass this — real JSON parsing must not
        val resp = resource().startOnboarding("""{"partyType":"INDIVIDUAL","legalNameSuffix":"Jana"}""")
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `invalid JSON body returns 400`() {
        val resp = resource().startOnboarding("not-json")
        assertThat(resp.status).isEqualTo(400)
        assertThat(resp.entity as String).contains("Invalid JSON")
    }

    // ---- legalName length ---------------------------------------------------

    @Test
    fun `legalName over 500 chars returns 400`() {
        val longName = "A".repeat(501)
        val resp = resource().startOnboarding("""{"partyType":"INDIVIDUAL","legalName":"$longName"}""")
        assertThat(resp.status).isEqualTo(400)
    }

    @Test
    fun `legalName exactly 500 chars is accepted`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.postAnonymous(any(), any()) } returns partyCreated()
        val name = "A".repeat(500)
        val resp = resource(upstream).startOnboarding("""{"partyType":"INDIVIDUAL","legalName":"$name"}""")
        assertThat(resp.status).isEqualTo(201)
    }
}
