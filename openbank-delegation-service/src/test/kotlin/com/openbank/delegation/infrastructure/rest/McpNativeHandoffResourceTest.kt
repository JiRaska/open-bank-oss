// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.infrastructure.persistence.repository.McpOAuthHandoffStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import io.vertx.core.http.HttpServerRequest
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class McpNativeHandoffResourceTest {
    private val store = mockk<McpOAuthHandoffStore>()
    private val enabled = McpNativeHandoffSettings(true, true)
    private val https = mockk<HttpServerRequest> { every { isSSL } returns true }
    private val token = "1".repeat(64)
    private val requestRef = "2".repeat(64)
    private val party = UUID.randomUUID()
    private val owner = McpOAuthHandoffStore.Claim(party, "realm-sub")
    private val binding = McpOAuthHandoffStore.Binding(
        requestRef = requestRef,
        issuer = "https://issuer.test/realms/customers",
        clientId = "synthetic-hosted-client",
        resource = "https://mcp.test/customer/mcp",
        redirectUri = "https://client.test/callback",
        sessionRef = "3".repeat(64),
        kind = McpOAuthHandoffStore.BindingKind.HOSTED,
        parJkt = null,
        scopes = setOf("openid"),
        expiresAtEpochSecond = Instant.now().plusSeconds(120).epochSecond,
    )

    @Test
    fun `issuer requires both feature switches and exact own account`() {
        val request = McpNativeHandoffIssuerResource.BindingRequest(token, binding)
        val issuer = identity("openbank-keycloak-mcp")
        assertThatThrownBy {
            val disabled = McpNativeHandoffIssuerResource(McpNativeHandoffSettings(true, false), store, issuer)
            disabled.request = https
            runBlocking { disabled.issue(token, null, request) }
        }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            val wrong = McpNativeHandoffIssuerResource(enabled, store, identity("openbank-edge"))
            wrong.request = https
            runBlocking { wrong.issue(token, null, request) }
        }.isInstanceOf(ForbiddenException::class.java)
        val allowed = McpNativeHandoffIssuerResource(enabled, store, issuer)
        allowed.request = https
        assertThatThrownBy { runBlocking { allowed.issue(token, party.toString(), request) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { runBlocking { allowed.issue(null, null, request) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { store.issue(any(), any()) }
        coEvery { store.issue(token, binding) } returns true
        assertThat(runBlocking { allowed.issue(token, null, request) }.status).isEqualTo(204)
    }

    @Test
    fun `edge claim uses authorized party and exposes only request reference`() {
        val request = McpNativeHandoffCustomerResource.ClaimRequest(token, owner.keycloakSubject)
        val wrongAccess = McpConnectCustomerAccess(identity("openbank-keycloak-mcp"), true)
        val wrong = McpNativeHandoffCustomerResource(enabled, wrongAccess, store)
        wrong.request = https
        assertThatThrownBy { runBlocking { wrong.claim(token, party, request) } }
            .isInstanceOf(ForbiddenException::class.java)
        val edgeAccess = McpConnectCustomerAccess(identity("openbank-edge"), true)
        val allowed = McpNativeHandoffCustomerResource(enabled, edgeAccess, store)
        allowed.request = https
        assertThatThrownBy { runBlocking { allowed.claim(token, null, request) } }
            .isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { store.claimAndRegister(any(), any()) }
        coEvery { store.claimAndRegister(token, owner) } returns requestRef
        assertThat(runBlocking { allowed.claim(token, party, request) }).isEqualTo(
            McpNativeHandoffCustomerResource.ClaimReceipt(requestRef),
        )
    }

    @Test
    fun `both authority resources require TLS even with the expected service identities`() {
        val http = mockk<HttpServerRequest> { every { isSSL } returns false }
        val issuer = McpNativeHandoffIssuerResource(enabled, store, identity("openbank-keycloak-mcp"))
        issuer.request = http
        val edgeAccess = McpConnectCustomerAccess(identity("openbank-edge"), true)
        val edge = McpNativeHandoffCustomerResource(enabled, edgeAccess, store)
        edge.request = http

        val issue = McpNativeHandoffIssuerResource.BindingRequest(token, binding)
        val claim = McpNativeHandoffCustomerResource.ClaimRequest(token, owner.keycloakSubject)
        assertThatThrownBy { runBlocking { issuer.issue(token, null, issue) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { runBlocking { edge.claim(token, party, claim) } }
            .isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { store.issue(any(), any()) }
        coVerify(exactly = 0) { store.claimAndRegister(any(), any()) }
    }

    private fun identity(client: String): SecurityIdentity = mockk {
        every { principal } returns mockk<JsonWebToken> {
            every { subject } returns UUID.randomUUID().toString()
            every { getClaim<Any?>("azp") } returns client
            every { getClaim<Any?>("preferred_username") } returns "service-account-$client"
        }
    }
}
