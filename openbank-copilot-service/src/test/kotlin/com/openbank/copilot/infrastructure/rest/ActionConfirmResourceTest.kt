// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.application.port.out.ProposalTokenStore
import com.openbank.copilot.application.port.out.ToolPolicyDecision
import com.openbank.copilot.application.port.out.ToolPolicyPort
import com.openbank.copilot.domain.ProposalToken
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val HTTP_UNPROCESSABLE_ENTITY = 422

class ActionConfirmResourceTest {

    private val tokenStore: ProposalTokenStore = mockk()
    private val opaGate: ToolPolicyPort = mockk()
    private val identity: SecurityIdentity = mockk()

    private lateinit var resource: ActionConfirmResource

    @BeforeEach
    fun setUp() {
        resource = ActionConfirmResource()
        resource.tokenStore = tokenStore
        resource.opaGate = opaGate
        resource.identity = identity
        // ADR-0100: the resource now reads the wall clock through an injected Clock
        // (Instant.now(clock) for token expiry). The test tokens are minted relative to
        // real time (Instant.now() ± seconds), so systemUTC keeps the expiry checks correct.
        resource.clock = Clock.systemUTC()
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
        every { opaGate.authorize(token.toolName, customerId, null) } returns ToolPolicyDecision.ALLOWED
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
        // The port is fail-closed and returns the deny; the resource is what maps it onto 403.
        every { opaGate.authorize(token.toolName, customerId, null) } returns
            ToolPolicyDecision.denied("opa-denied: policy")

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
