// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CustomerStatutoryDelegationResourceTest {
    private val upstream = mockk<UpstreamClient>()
    private val resolver = mockk<ActingForResolver>()
    private val jwt = mockk<JsonWebToken>()
    private val mapper = ObjectMapper()
    private val actor = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val digest = "b".repeat(64)
    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val resource = CustomerStatutoryDelegationResource(
        upstream,
        resolver,
        mapper,
        jwt,
        Clock.fixed(now, ZoneOffset.UTC),
        DELEGATION,
        SCA,
    )

    init {
        every { jwt.getClaim<String>("party_id") } returns actor.toString()
        every { jwt.subject } returns "synthetic-customer-subject"
        every { resolver.resolve(actor, company.toString()) } returns company
    }

    @Test
    fun `the edge creates a challenge for the JWT actor using only current scoped terms`() {
        val body = slot<String>()
        val authority = mapOf("X-Customer-Actor-Party-Id" to actor.toString())
        every { upstream.getWithHeaders("$DELEGATION$OPERATIONS/$operation", company.toString(), authority) } returns
            Response.ok(operationBody(now.plusSeconds(60))).build()
        every {
            upstream.post("$SCA/api/v1/sca/challenges", actor.toString(), capture(body), "fixture-request-key")
        } returns Response.status(201).build()

        val response = resource.challenge(operation, company.toString(), "fixture-request-key")
        val request = mapper.readTree(body.captured)
        assertThat(response.status).isEqualTo(201)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store")
        assertThat(request.path("partyId").asText()).isEqualTo(actor.toString())
        assertThat(request.path("onBehalfOfPartyId").asText()).isEqualTo(company.toString())
        assertThat(request.path("purpose").asText()).isEqualTo("APPROVAL")
        assertThat(request.at("/dynamicLinkingData/approvalRequestId").asText())
            .isEqualTo("delegation-statutory-issue:$operation")
        assertThat(request.at("/dynamicLinkingData/payloadSha256").asText()).isEqualTo(digest)
    }

    @Test
    fun `personal or mismatched company requests cannot reach the upstream operation`() {
        every { resolver.resolve(actor, null) } returns actor
        assertThatThrownBy { resource.pending(null) }.isInstanceOf(ForbiddenException::class.java)
        val other = mapper.readTree("""{"draft":{"grantorPartyId":"${UUID.randomUUID()}"}}""")
        assertThatThrownBy {
            resource.issue(other, company.toString(), "fixture-request-key")
        }.isInstanceOf(ForbiddenException::class.java)
        verify { upstream wasNot Called }
    }

    @Test
    fun `expired terms cannot start a device approval`() {
        every {
            upstream.getWithHeaders(
                "$DELEGATION$OPERATIONS/$operation",
                company.toString(),
                mapOf("X-Customer-Actor-Party-Id" to actor.toString()),
            )
        } returns Response.ok(operationBody(now)).build()
        assertThat(resource.challenge(operation, company.toString(), "fixture-request-key").status).isEqualTo(409)
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `the edge derives stable keys for statutory signing and closing`() {
        val challenge = UUID.randomUUID().toString()
        val authority = mapOf("X-Customer-Actor-Party-Id" to actor.toString())
        val signature = """{"scaChallengeId":"$challenge"}"""
        val cancellation = """{"cancel":true}"""
        every {
            upstream.post("$DELEGATION$OPERATIONS/$operation/sign", company.toString(), signature, challenge, authority)
        } returns Response.ok().build()
        every {
            upstream.post(
                "$DELEGATION$OPERATIONS/$operation/close",
                company.toString(),
                cancellation,
                operation.toString(),
                authority,
            )
        } returns Response.ok().build()

        assertThat(resource.sign(operation, mapper.readTree(signature), company.toString()).status).isEqualTo(200)
        assertThat(resource.close(operation, mapper.readTree(cancellation), company.toString()).status).isEqualTo(200)
    }

    private fun operationBody(expiresAt: Instant) = """
        {"id":"$operation","companyPartyId":"$company","action":"ISSUE","status":"COLLECTING",
        "signedByCaller":false,"expiresAt":"$expiresAt","approvalRequestId":"delegation-statutory-issue:$operation",
        "payloadSha256":"$digest"}
    """.trimIndent()

    private companion object {
        const val DELEGATION = "http://delegation.example.invalid"
        const val SCA = "http://sca.example.invalid"
        const val OPERATIONS = "/api/v1/delegations/statutory-operations"
    }
}
