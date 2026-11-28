// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.`in`.BalanceUseCase
import com.openbank.balance.infrastructure.client.AccountServiceClient
import com.openbank.balance.infrastructure.client.McpBalanceGrantClient
import com.openbank.balance.infrastructure.client.McpBalanceGrantRequest
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
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.UUID

class McpBalanceReadIdentityTest {
    private val account = UUID.randomUUID()
    private val party = UUID.randomUUID()
    private val grant = UUID.randomUUID()
    private val connection = UUID.fromString("677fcabd-643d-458b-bb54-b7e785ae84d0")
    private val connectionClaim = "openbank_mcp_connection_id"
    private val issuer = "https://identity.test/realms/openbank-customers"
    private val actor = "openbank-mcp-read-delegation"
    private val jwt = mockk<JsonWebToken>()
    private val request = mockk<HttpServerRequest>()

    init {
        val der = byteArrayOf(1, 2, 3)
        val leaf = mockk<X509Certificate>()
        every { leaf.encoded } returns der
        every { request.isSSL } returns true
        every { request.connection().peerCertificates() } returns listOf(leaf)
        every { jwt.issuer } returns issuer
        every { jwt.subject } returns "synthetic-customer"
        every { jwt.audience } returns setOf("openbank-balance-service")
        every { jwt.claimNames } returns emptySet()
        every { jwt.getClaim<Any?>(connectionClaim) } returns null
        every { jwt.getClaim<String>("scope") } returns "mcp-customer-balance-read"
        every { jwt.getClaim<String>("azp") } returns actor
        every { jwt.getClaim<String>("account_id") } returns account.toString()
        every { jwt.getClaim<String>("party_id") } returns party.toString()
        every { jwt.getClaim<String>("grant_id") } returns grant.toString()
        every { jwt.getClaim<Map<*, *>>("act") } returns mapOf(
            "sub" to actor,
            "act" to mapOf("sub" to "sandbox-client"),
        )
        val thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(der),
        )
        every { jwt.getClaim<Map<*, *>>("cnf") } returns mapOf("x5t#S256" to thumbprint)
    }

    @Test
    fun `bank token resolves the exact account on its bound TLS connection`() {
        assertThat(resolve()).isEqualTo(McpBalanceReadIdentity.Binding(party, grant, "sandbox-client"))
    }

    @Test
    fun `wrong account or multiple audiences are refused`() {
        assertThat(McpBalanceReadIdentity.resolve(jwt, UUID.randomUUID(), issuer, request)).isNull()
        every { jwt.audience } returns setOf("openbank-balance-service", "openbank-mcp-service")
        assertThat(resolve()).isNull()
    }

    @Test
    fun `missing TLS or a different peer certificate are refused`() {
        every { request.isSSL } returns false
        assertThat(resolve()).isNull()
        every { request.isSSL } returns true
        every { jwt.getClaim<Map<*, *>>("cnf") } returns mapOf("x5t#S256" to "A".repeat(43))
        assertThat(resolve()).isNull()
    }

    @Test
    fun `route reads only after ownership and the live grant allow it`() {
        val balances = mockk<BalanceUseCase>()
        val accounts = mockk<AccountServiceClient>()
        val grants = mockk<McpBalanceGrantClient>()
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns jwt
        val resource = McpBalanceReadResource(balances, accounts, grants, true, Optional.of(issuer), false).also {
            it.identity = identity
            it.request = request
        }
        val binding = McpBalanceGrantRequest(grant, party, "sandbox-client", account)
        coEvery { accounts.getPartyId(account) } returns UUID.randomUUID()
        assertThrows<NotFoundException> { runBlocking { resource.read(account) } }
        coVerify(exactly = 0) { grants.isActive(any()) }

        coEvery { accounts.getPartyId(account) } returns party
        coEvery { grants.isActive(binding) } returns false
        assertThrows<ForbiddenException> { runBlocking { resource.read(account) } }
        coVerify(exactly = 0) { balances.getBalances(any()) }

        coEvery { grants.isActive(binding) } returns true
        coEvery { balances.getBalances(account) } returns emptyList()
        assertThat(runBlocking { resource.read(account) }.status).isEqualTo(200)
        coVerify(exactly = 1) { balances.getBalances(account) }
    }

    @Test
    fun `enabled mode requires the exact canonical connection claim`() {
        assertThat(resolve(connectionBindingEnabled = true)).isNull()
        every { jwt.getClaim<Any?>(connectionClaim) } returns 7
        assertThat(resolve(connectionBindingEnabled = true)).isNull()
        every { jwt.getClaim<Any?>(connectionClaim) } returns connection.toString().uppercase(Locale.ROOT)
        assertThat(resolve(connectionBindingEnabled = true)).isNull()
        every { jwt.getClaim<Any?>(connectionClaim) } returns connection.toString()
        assertThat(resolve(connectionBindingEnabled = true))
            .isEqualTo(McpBalanceReadIdentity.Binding(party, grant, "sandbox-client", connection))
    }

    @Test
    fun `legacy mode refuses even a null V2 connection claim`() {
        every { jwt.claimNames } returns setOf(connectionClaim)
        assertThat(resolve()).isNull()
        every { jwt.getClaim<Any?>(connectionClaim) } returns connection.toString()
        assertThat(resolve()).isNull()
    }

    @Test
    fun `route forwards only the verified connection and still requires the live grant`() {
        val balances = mockk<BalanceUseCase>()
        val accounts = mockk<AccountServiceClient>()
        val grants = mockk<McpBalanceGrantClient>()
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns jwt
        val resource = McpBalanceReadResource(balances, accounts, grants, true, Optional.of(issuer), true).also {
            it.identity = identity
            it.request = request
        }
        assertThrows<ForbiddenException> { runBlocking { resource.read(account) } }
        coVerify(exactly = 0) { accounts.getPartyId(any()) }
        coVerify(exactly = 0) { grants.isActive(any()) }

        every { jwt.getClaim<Any?>(connectionClaim) } returns connection.toString()
        coEvery { accounts.getPartyId(account) } returns party
        val request = McpBalanceGrantRequest(grant, party, "sandbox-client", account, connection)
        coEvery { grants.isActive(request) } returns false
        assertThrows<ForbiddenException> { runBlocking { resource.read(account) } }
        coVerify(exactly = 0) { balances.getBalances(any()) }

        coEvery { grants.isActive(request) } returns true
        coEvery { balances.getBalances(account) } returns emptyList()
        assertThat(runBlocking { resource.read(account) }.status).isEqualTo(200)
        coVerify(exactly = 2) { grants.isActive(request) }
        coVerify(exactly = 1) { balances.getBalances(account) }
    }

    @Test
    fun `delegation wire body carries connection only in bound mode`() {
        val mapper = ObjectMapper()
        val legacy = McpBalanceGrantRequest(grant, party, "sandbox-client", account)
        assertThat(mapper.valueToTree<JsonNode>(legacy).has("connectionId")).isFalse()
        val bound = legacy.copy(connectionId = connection)
        assertThat(mapper.valueToTree<JsonNode>(bound)["connectionId"].asText()).isEqualTo(connection.toString())
    }

    private fun resolve(connectionBindingEnabled: Boolean = false) =
        McpBalanceReadIdentity.resolve(jwt, account, issuer, request, connectionBindingEnabled)
}
