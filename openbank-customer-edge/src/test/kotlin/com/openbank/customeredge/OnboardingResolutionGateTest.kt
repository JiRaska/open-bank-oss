// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Unit tests for the identity-resolution dedup gate on /onboarding/register (ADR-0072 §6 / ADR-0094).
 *
 * The gate is flag-gated (default off) and only fires when a birthdate is present. When on it calls
 * pid /resolve before party-service creates the (sub-keyed) party, so one human resolves to one
 * party across channels. A resolver outage fails open (the create still happens) so onboarding is
 * never blocked by it.
 */
class OnboardingResolutionGateTest {

    private val caller = UUID.randomUUID()
    private val existingParty = UUID.randomUUID()

    private fun resource(upstream: UpstreamClient, resolutionEnabled: Boolean): CustomerEdgeResource =
        CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns caller.toString()
                every { getClaim<String>("name") } returns "Jan Novák"
                every { getClaim<String>("email") } returns "jan@example.cz"
                every { subject } returns caller.toString()
            }
            keycloakAdmin = mockk(relaxed = true)
            objectMapper = ObjectMapper()
            partyServiceUrl = "http://party"
            pidServiceUrl = "http://pid"
            identityResolutionEnabled = resolutionEnabled
        }

    private val bodyWithDob = """{"legalName":"Jan Novák","email":"jan@example.cz","dateOfBirth":"1985-03-12"}"""

    private fun createOk() = Response.status(201).entity("""{"id":"$caller","status":"PENDING_KYC"}""").build()

    private fun resolveVerdict(json: String) = Response.ok(json).build()

    private fun isResolve(url: String) = url.contains("/parties/resolve")
    private fun isCreate(url: String) = url.endsWith("/api/v1/parties")
    private fun isLink(url: String) = url.contains("/external-ids")
    private fun isRegister(url: String) = url.contains("/register-identity")

    @Test
    fun `flag off — resolve is never called, party is created directly`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { isCreate(it) }, any(), any(), any()) } returns createOk()

        val resp = resource(upstream, resolutionEnabled = false).registerParty(bodyWithDob)

        assertThat(resp.status).isEqualTo(201)
        verify(exactly = 0) { upstream.post(match { isResolve(it) }, any(), any(), any()) }
    }

    @Test
    fun `NO_MATCH — resolve runs, the party is created, then the identity is indexed in pid`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { isResolve(it) }, any(), any(), any()) } returns
            resolveVerdict("""{"decision":"NO_MATCH","partyId":null,"caseId":null,"candidates":null}""")
        every { upstream.post(match { isCreate(it) }, any(), any(), any()) } returns createOk()
        every { upstream.post(match { isRegister(it) }, any(), any(), any()) } returns Response.ok().build()

        val resp = resource(upstream, resolutionEnabled = true).registerParty(bodyWithDob)

        assertThat(resp.status).isEqualTo(201)
        verify(exactly = 1) { upstream.post(match { isResolve(it) }, any(), any(), any()) }
        verify(exactly = 1) { upstream.post(match { isCreate(it) }, any(), any(), any()) }
        // pid resolver index populated with the onboarded identity (issue #1294).
        verify(exactly = 1) { upstream.post(match { isRegister(it) }, any(), any(), any()) }
    }

    @Test
    fun `flag off — the identity is not indexed in pid`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { isCreate(it) }, any(), any(), any()) } returns createOk()

        resource(upstream, resolutionEnabled = false).registerParty(bodyWithDob)

        verify(exactly = 0) { upstream.post(match { isRegister(it) }, any(), any(), any()) }
    }

    @Test
    fun `MATCH_EXISTING — returns the existing party and never creates a duplicate`() {
        val upstream = mockk<UpstreamClient>()
        val verdict = """{"decision":"MATCH_EXISTING","partyId":"$existingParty","caseId":null,"candidates":null}"""
        every { upstream.post(match { isResolve(it) }, any(), any(), any()) } returns resolveVerdict(verdict)
        every { upstream.post(match { isLink(it) }, any(), any(), any()) } returns Response.ok().build()

        val resp = resource(upstream, resolutionEnabled = true).registerParty(bodyWithDob)

        assertThat(resp.status).isEqualTo(200)
        assertThat((resp.entity as String)).contains(existingParty.toString()).contains("MATCHED_EXISTING")
        verify(exactly = 0) { upstream.post(match { isCreate(it) }, any(), any(), any()) }
        // The new Keycloak sub is linked to the existing golden-record party (ADR-0072 §5).
        verify(exactly = 1) { upstream.post(match { isLink(it) }, any(), any(), any()) }
    }

    @Test
    fun `MATCH_EXISTING — a failed re-link is best-effort and still returns the existing party`() {
        val upstream = mockk<UpstreamClient>()
        val verdict = """{"decision":"MATCH_EXISTING","partyId":"$existingParty","caseId":null,"candidates":null}"""
        every { upstream.post(match { isResolve(it) }, any(), any(), any()) } returns resolveVerdict(verdict)
        every { upstream.post(match { isLink(it) }, any(), any(), any()) } returns Response.status(500).build()

        val resp = resource(upstream, resolutionEnabled = true).registerParty(bodyWithDob)

        assertThat(resp.status).isEqualTo(200)
        assertThat((resp.entity as String)).contains(existingParty.toString()).contains("MATCHED_EXISTING")
        verify(exactly = 0) { upstream.post(match { isCreate(it) }, any(), any(), any()) }
    }

    @Test
    fun `NEEDS_MANUAL_VERIFICATION — neutral pending, no create, no candidate leak`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { isResolve(it) }, any(), any(), any()) } returns
            resolveVerdict(
                """{"decision":"NEEDS_MANUAL_VERIFICATION","partyId":null,"caseId":null,""" +
                    """"candidates":[{"partyId":"$existingParty","nameMasked":"N. J.","birthYear":1985}]}""",
            )

        val resp = resource(upstream, resolutionEnabled = true).registerParty(bodyWithDob)

        assertThat(resp.status).isEqualTo(202)
        val entity = resp.entity as String
        assertThat(entity).contains("VERIFICATION_PENDING")
        assertThat(entity).doesNotContain(existingParty.toString())
        verify(exactly = 0) { upstream.post(match { isCreate(it) }, any(), any(), any()) }
    }

    @Test
    fun `resolver unavailable — fails open and creates the party`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { isResolve(it) }, any(), any(), any()) } returns
            Response.status(503).entity("down").build()
        every { upstream.post(match { isCreate(it) }, any(), any(), any()) } returns createOk()

        val resp = resource(upstream, resolutionEnabled = true).registerParty(bodyWithDob)

        assertThat(resp.status).isEqualTo(201)
        verify(exactly = 1) { upstream.post(match { isCreate(it) }, any(), any(), any()) }
    }

    @Test
    fun `no birthdate — gate is skipped even when enabled, party is created`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { isCreate(it) }, any(), any(), any()) } returns createOk()

        val resp = resource(upstream, resolutionEnabled = true)
            .registerParty("""{"legalName":"Jan Novák","email":"jan@example.cz"}""")

        assertThat(resp.status).isEqualTo(201)
        verify(exactly = 0) { upstream.post(match { isResolve(it) }, any(), any(), any()) }
    }
}
