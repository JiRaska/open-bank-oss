// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * SCA under `X-Acting-For` is the HUMAN's (PSD2 RTS Art. 4 authenticates the payment service
 * user; a company has no device of its own). Before this, the header switched every SCA route to
 * the ENTITY: challenges were raised for the company, `/sca/pending` listed every mandate holder's
 * approvals to every other one, a device could be enrolled to the company, and a decision was
 * posted and audited as the company — so any co-representative's phone could approve a colleague's
 * payment and the record could not say who did.
 */
class ScaActingForBindingTest {

    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val party = "http://party"
    private val sca = "http://sca"

    private fun upstream(mandate: Boolean = true): UpstreamClient = mockk<UpstreamClient>(relaxed = true).also { u ->
        every { u.get("$party/api/v1/parties/$human/acting-for", human.toString()) } returns Response.ok(
            if (mandate) """[{"partyId":"$company","partyType":"COMPANY","status":"ACTIVE"}]""" else "[]",
        ).build()
        every { u.get(match { it.startsWith(sca) }, any()) } returns Response.ok("[]").build()
        every { u.post(match { it.startsWith(sca) }, any(), any(), any()) } returns Response.ok("{}").build()
    }

    private fun resource(upstream: UpstreamClient, audit: EdgeAuditPublisher = mockk(relaxed = true)) =
        CustomerEdgeResource(
            upstream,
            audit,
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            actingForResolver = ActingForResolver(upstream, ObjectMapper(), Clock.systemUTC(), party, true)
            requestHeaders = mockk { every { getHeaderString("X-Acting-For") } returns company.toString() }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns human.toString()
                every { subject } returns human.toString()
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = party
            scaServiceUrl = sca
        }

    @Test
    fun `pending approvals under X-Acting-For are the human's, not the company's`() {
        val upstream = upstream()
        resource(upstream).listPendingSca()

        verify { upstream.get("$sca/api/v1/sca/parties/$human/challenges/pending", human.toString()) }
        verify(exactly = 0) { upstream.get(match { it.contains("/parties/$company/") }, any()) }
    }

    @Test
    fun `a decision under X-Acting-For is posted and audited as the human, the company only as context`() {
        val upstream = upstream()
        val audit = mockk<EdgeAuditPublisher>(relaxed = true)
        val challenge = UUID.randomUUID()
        val details = slot<Map<String, String?>>()

        resource(
            upstream,
            audit,
        ).recordDecision(challenge, """{"decision":"APPROVED","credentialId":"c","signature":"s"}""")

        verify { upstream.post("$sca/api/v1/sca/challenges/$challenge/decision", human.toString(), any(), any()) }
        verify {
            audit.emit(
                eventType = "SCA_DECISION_RECORDED",
                partyId = human.toString(),
                operation = "sca.decision",
                result = "SUCCESS",
                resourceId = challenge.toString(),
                details = capture(details),
            )
        }
        assertThat(details.captured["actingForPartyId"]).isEqualTo(company.toString())
    }

    @Test
    fun `a challenge raised under X-Acting-For binds to the human even if the body names the company`() {
        val upstream = upstream()
        val sent = slot<String>()
        every { upstream.post("$sca/api/v1/sca/challenges", human.toString(), capture(sent), any()) } returns
            Response.status(201).entity("{}").build()

        val body = ObjectMapper().readTree("""{"partyId":"$company","purpose":"PAYMENT"}""")
        resource(upstream).initiateChallenge(body, null)

        assertThat(ObjectMapper().readTree(sent.captured).path("partyId").asText()).isEqualTo(human.toString())
    }

    @Test
    fun `a device cannot be enrolled to the company, only to the human`() {
        val upstream = upstream()
        val toCompany = resource(upstream).enrollDevice(company, """{"credentialId":"c"}""")

        assertThat(toCompany.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/parties/$company/devices") }, any(), any(), any()) }
    }

    @Test
    fun `without an active mandate every SCA route under X-Acting-For is a 403 and sca-service is never called`() {
        val upstream = upstream(mandate = false)
        val r = resource(upstream)

        assertThatThrownBy { r.listPendingSca() }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { r.recordDecision(UUID.randomUUID(), """{"decision":"APPROVED"}""") }
            .isInstanceOf(ForbiddenException::class.java)
        verify(exactly = 0) { upstream.get(match { it.startsWith(sca) }, any()) }
        verify(exactly = 0) { upstream.post(match { it.startsWith(sca) }, any(), any(), any()) }
    }
}
