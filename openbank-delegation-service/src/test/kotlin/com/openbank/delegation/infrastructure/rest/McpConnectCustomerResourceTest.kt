// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.infrastructure.persistence.repository.McpConnectRequestStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class McpConnectCustomerResourceTest {
    private val store = mockk<McpConnectRequestStore>()
    private val ownership = mockk<ResourceOwnershipClient>()
    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val reference = "a".repeat(64)

    @Test
    fun `only the verified edge can list connection requests`() {
        val resource = resource(client = "openbank-services")
        assertThatThrownBy { runBlocking { resource.list(party) } }.isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { store.listForParty(any()) }
    }

    @Test
    fun `selection rejects an account whose ownership is unavailable`() {
        pendingReview()
        coEvery { ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, account) } returns
            OwnershipVerdict.UNVERIFIABLE
        val input = McpConnectCustomerResource.Selection(
            listOf(McpConnectRequestStore.AccountSelection(account, setOf("ACCOUNT_READ_BALANCES"))),
        )
        assertThatThrownBy { runBlocking { resource().select(party, reference, input) } }
            .isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { store.select(any(), any(), any()) }
    }

    @Test
    fun `null selected account is rejected before ownership and storage`() {
        pendingReview()
        val input = McpConnectCustomerResource.Selection(listOf(null))
        assertThatThrownBy { runBlocking { resource().select(party, reference, input) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("accounts[0] must not be null")
        coVerify(exactly = 0) { ownership.verifyOwnership(any(), any(), any()) }
        coVerify(exactly = 0) { store.select(any(), any(), any()) }
    }

    @Test
    fun `null registration scope is rejected before creating the storage model`() {
        val registration = McpConnectIssuerResource.Registration(
            reference,
            party,
            "mcp-local-reference",
            "A".repeat(43),
            "example.test",
            "https://example.test/customer/mcp",
            setOf("openid", null),
            OffsetDateTime.now().plusMinutes(1).toEpochSecond(),
        )
        assertThatThrownBy { registration.toRequest() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("scopes[1] must not be null")
    }

    @Test
    fun `revocation uses the verified customer's party and returns 204 only on success`() {
        val connectionId = UUID.randomUUID()
        coEvery { store.revoke(party, connectionId) } returns true
        coEvery { store.revoke(OTHER_PARTY, connectionId) } returns false
        val resource = resource()
        assertThat(runBlocking { resource.revoke(party, connectionId).status }).isEqualTo(204)
        assertThatThrownBy { runBlocking { resource.revoke(OTHER_PARTY, connectionId) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    private fun pendingReview() {
        val request = McpConnectRequestStore.Request(
            reference,
            party,
            "mcp-local-reference",
            "A".repeat(43),
            "example.test",
            "https://example.test/customer/mcp",
            setOf("openid"),
            OffsetDateTime.now().plusMinutes(1),
        )
        coEvery { store.findForParty(party, reference) } returns
            McpConnectRequestStore.Review(request, emptyList(), null, null, null)
    }

    private fun resource(client: String = "openbank-edge"): McpConnectCustomerResource {
        val token = mockk<JsonWebToken>()
        every { token.subject } returns "subject"
        every { token.getClaim<Any?>("azp") } returns client
        every { token.getClaim<Any?>("preferred_username") } returns "service-account-$client"
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns token
        return McpConnectCustomerResource(identity, store, ownership, enabled = true)
    }

    private companion object {
        val OTHER_PARTY: UUID = UUID.randomUUID()
    }
}
