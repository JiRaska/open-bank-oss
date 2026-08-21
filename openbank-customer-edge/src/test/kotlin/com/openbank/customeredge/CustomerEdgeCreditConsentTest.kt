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
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * ADR-0269 rule 1 — the customer's own credit consents.
 *
 * These tests are written against the ways the route could WRONGLY GRANT or wrongly report a grant,
 * because that is the failure with consequences: a customer who believes offers are off and is
 * offered anyway. The happy path is one test; the rest are refusals and non-actions.
 */
class CustomerEdgeCreditConsentTest {

    private val party = UUID.randomUUID()

    private fun newResource(upstream: UpstreamClient): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns null
            every { subject } returns party.toString()
        }
        objectMapper = ObjectMapper()
        consentServiceUrl = "http://consent"
    }

    private fun consentList(vararg scopes: String, status: String = "ACTIVE"): String =
        scopes.joinToString(",", "[", "]") { s ->
            """{"id":"${UUID.nameUUIDFromBytes(s.toByteArray())}","status":"$status","scopes":["$s"]}"""
        }

    private fun upstreamReturning(list: String): UpstreamClient = mockk(relaxed = true) {
        every { get(match { it.contains("/api/v1/consents/party/") }, any()) } returns Response.ok(list).build()
    }

    private fun bodyOf(response: Response): Map<String, Any?> {
        val node = ObjectMapper().readTree(response.entity.toString())
        return node.fields().asSequence().associate { (k, v) -> k to (if (v.isBoolean) v.asBoolean() else v) }
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    @Test
    fun `a party with no consents reads as everything off`() {
        val resp = newResource(upstreamReturning("[]")).getCreditConsents()
        assertThat(bodyOf(resp)).containsEntry("offers", false)
            .containsEntry("profileUse", false)
            .containsEntry("aiAgent", false)
    }

    @Test
    fun `an unreadable consent list reads as everything off, never as granted`() {
        // The client uses this to decide whether to fetch offers at all. An optimistic default here
        // would fetch offers for a customer whose consent could not be read.
        val upstream = mockk<UpstreamClient>(relaxed = true) {
            every { get(any(), any()) } returns Response.status(503).build()
        }
        assertThat(bodyOf(newResource(upstream).getCreditConsents())).containsEntry("offers", false)
    }

    @Test
    fun `only ACTIVE consents count as granted`() {
        val revoked = consentList("CREDIT_OFFERS", status = "REVOKED")
        assertThat(bodyOf(newResource(upstreamReturning(revoked)).getCreditConsents()))
            .containsEntry("offers", false)
    }

    @Test
    fun `each switch reports its own scope and not another`() {
        val body = bodyOf(newResource(upstreamReturning(consentList("CREDIT_PROFILE_USE"))).getCreditConsents())
        assertThat(body).containsEntry("profileUse", true)
            .containsEntry("offers", false)
            .containsEntry("aiAgent", false)
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    @Test
    fun `granting posts one consent for the requested scope, scoped to the caller's own party`() {
        val upstream = upstreamReturning("[]")
        val bodySlot = slot<String>()
        every { upstream.post(match { it.endsWith("/api/v1/consents") }, any(), capture(bodySlot)) } returns
            Response.status(201).entity("{}").build()

        newResource(upstream).putCreditConsents("""{"offers":true,"profileUse":false,"aiAgent":false}""")

        verify(exactly = 1) { upstream.post(any(), party.toString(), any()) }
        assertThat(bodySlot.captured).contains("CREDIT_OFFERS").contains(party.toString())
        assertThat(bodySlot.captured).doesNotContain("CREDIT_AI_AGENT")
    }

    @Test
    fun `an omitted field is false — absence of consent is denial, never "leave it as it was"`() {
        val upstream = upstreamReturning(consentList("CREDIT_OFFERS"))
        newResource(upstream).putCreditConsents("{}")
        // Offers were on and the body did not mention them, so they are revoked. A partial-patch
        // reading would have left them on, which is the difference between a switch and a suggestion.
        verify(exactly = 1) { upstream.delete(match { it.contains("/api/v1/consents/") }, party.toString(), any()) }
    }

    @Test
    fun `turning a granted switch off revokes it and grants nothing`() {
        val upstream = upstreamReturning(consentList("CREDIT_OFFERS"))
        newResource(upstream).putCreditConsents("""{"offers":false,"profileUse":false,"aiAgent":false}""")
        verify(exactly = 1) { upstream.delete(any(), party.toString(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
    }

    @Test
    fun `re-granting an already-held consent does nothing rather than churning the audit trail`() {
        val upstream = upstreamReturning(consentList("CREDIT_OFFERS"))
        newResource(upstream).putCreditConsents("""{"offers":true,"profileUse":false,"aiAgent":false}""")
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
        verify(exactly = 0) { upstream.delete(any(), any(), any()) }
    }

    @Test
    fun `a malformed body changes nothing`() {
        val upstream = upstreamReturning("[]")
        val resp = newResource(upstream).putCreditConsents("not json")
        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
        verify(exactly = 0) { upstream.delete(any(), any(), any()) }
    }

    @Test
    fun `an unreadable consent list refuses the write rather than granting blindly`() {
        val upstream = mockk<UpstreamClient>(relaxed = true) {
            every { get(any(), any()) } returns Response.status(503).build()
        }
        val resp = newResource(upstream).putCreditConsents("""{"offers":true}""")
        assertThat(resp.status).isEqualTo(503)
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
    }
}
