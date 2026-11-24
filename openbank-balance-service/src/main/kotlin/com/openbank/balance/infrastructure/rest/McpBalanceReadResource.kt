// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.port.`in`.BalanceUseCase
import com.openbank.balance.infrastructure.client.AccountServiceClient
import com.openbank.balance.infrastructure.client.McpBalanceGrantClient
import com.openbank.balance.infrastructure.client.McpBalanceGrantRequest
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.Optional
import java.util.UUID

@Authenticated
@Path("/api/v1/mcp-balances")
@Produces(MediaType.APPLICATION_JSON)
class McpBalanceReadResource(
    private val balances: BalanceUseCase,
    private val accounts: AccountServiceClient,
    private val grants: McpBalanceGrantClient,
    @ConfigProperty(name = "openbank.balance.mcp.enabled", defaultValue = "false") private val enabled: Boolean,
    @ConfigProperty(name = "openbank.balance.mcp.issuer") private val issuer: Optional<String>,
    @ConfigProperty(name = "openbank.balance.mcp.connection-binding.enabled", defaultValue = "false")
    private val connectionBindingEnabled: Boolean,
) {
    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var request: HttpServerRequest

    @GET
    @Path("/{accountId}")
    suspend fun read(@PathParam("accountId") accountId: UUID?): Response {
        val account = requireEnabledAccount(accountId)
        val binding = requireBinding(account)
        if (accounts.getPartyId(account) != binding.partyId) throw NotFoundException()
        val liveGrant = McpBalanceGrantRequest(
            binding.grantId,
            binding.partyId,
            binding.clientId,
            account,
            binding.connectionId,
        )
        if (!grants.isActive(liveGrant)) {
            throw ForbiddenException("MCP balance grant is not active")
        }
        return Response.ok(mapOf("balances" to balances.getBalances(account))).build()
    }

    private fun requireEnabledAccount(accountId: UUID?): UUID {
        if (!enabled) throw NotFoundException()
        return requireNotNull(accountId) { "accountId is required" }
    }

    private fun requireBinding(account: UUID): McpBalanceReadIdentity.Binding {
        val jwt = identity.principal as? JsonWebToken ?: throw ForbiddenException("Verified JWT required")
        return McpBalanceReadIdentity.resolve(jwt, account, issuer.orElse(""), request, connectionBindingEnabled)
            ?: throw ForbiddenException("Invalid MCP balance-read identity")
    }
}
