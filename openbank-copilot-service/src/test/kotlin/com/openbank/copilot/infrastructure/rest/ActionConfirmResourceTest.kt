// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.domain.ProposalToken
import com.openbank.copilot.infrastructure.authz.OpaToolGate
import com.openbank.copilot.infrastructure.persistence.ProposalTokenStore
import io.mockk.every
import io.mockk.just
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private const val HTTP_UNPROCESSABLE_ENTITY = 422

class ActionConfirmResourceTest {

    private val tokenStore: ProposalTokenStore = mockk()
    private val opaGate: OpaToolGate = mockk()
    private val identity: SecurityIdentity = mockk()

    private lateinit var resource: ActionConfirmResource

    @BeforeEach
    fun setUp() {
        resource = ActionConfirmResource()
        resource.tokenStore = tokenStore
        resource.opaGate = opaGate
        resource.identity = identity
    }

    private fun stubIdentity(sub: String) {
        val jwt = mockk<JsonWebToken>()
        every { jwt.subject } returns sub
        every { identity.principal } returns jwt
    }

    private fun validToken(customerId: String): ProposalToken = ProposalToken(
        id = UUID.randomUUID(),
        toolName = "propose_payment",
        params = mapOf("amount" to "10.00"),
        expiresAt = Instant.now().plusSeconds(60),
        customerId = customerId,
    )

    @Test
    fun `returns 404 for unknown token`() {
        stubIdentity("cust-1")
        val tokenId = UUID.randomUUID()
        every { tokenStore.find(tokenId) } returns null

        val response = resource.confirm(tokenId.toString())

        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
    }

    @Test
    fun `returns 422 for expired token`() {
        val customerId = "cust-2"
        stubIdentity(customerId)
        val expired = ProposalToken(
            id = UUID.randomUUID(),
            toolName = "propose_payment",
            params = emptyMap(),
            expiresAt = Instant.now().minusSeconds(10),
            customerId = customerId,
        )
        every { tokenStore.find(expired.id) } returns expired
        every { tokenStore.delete(expired.id) } just runs

        val response = resource.confirm(expired.id.toString())

        assertThat(response.status).isEqualTo(HTTP_UNPROCESSABLE_ENTITY)
        verify { tokenStore.delete(expired.id) }
    }

    @Test
    fun `returns 403 when customerId does not match JWT sub`() {
        stubIdentity("attacker")
        val token = validToken("cust-3")
        every { tokenStore.find(token.id) } returns token

        val response = resource.confirm(token.id.toString())

        assertThat(response.status).isEqualTo(Response.Status.FORBIDDEN.statusCode)
    }

    @Test
    fun `returns 200 with CONFIRMED and actionId on happy path`() {
        val customerId = "cust-4"
        stubIdentity(customerId)
        val token = validToken(customerId)
        every { tokenStore.find(token.id) } returns token
        justRun { opaGate.authorize(token.toolName, customerId, null) }
        every { tokenStore.delete(token.id) } just runs

        val response = resource.confirm(token.id.toString())

        assertThat(response.status).isEqualTo(Response.Status.OK.statusCode)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, String>
        assertThat(body["status"]).isEqualTo("CONFIRMED")
        assertThat(body["actionId"]).isNotBlank()
        verify { tokenStore.delete(token.id) }
    }

    @Test
    fun `returns 403 when OPA denies`() {
        val customerId = "cust-5"
        stubIdentity(customerId)
        val token = validToken(customerId)
        every { tokenStore.find(token.id) } returns token
        every { opaGate.authorize(token.toolName, customerId, null) } answers { throw WebApplicationException(403) }

        val response = resource.confirm(token.id.toString())

        assertThat(response.status).isEqualTo(Response.Status.FORBIDDEN.statusCode)
    }

    @Test
    fun `returns 404 for non-UUID tokenId string`() {
        stubIdentity("cust-6")

        val response = resource.confirm("not-a-uuid")

        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
    }
}
