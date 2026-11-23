// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.client

import com.fasterxml.jackson.annotation.JsonInclude
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

data class McpBalanceGrantRequest(
    val grantId: UUID,
    val partyId: UUID,
    val clientId: String,
    val accountId: UUID,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val connectionId: UUID? = null,
)

@RegisterRestClient(configKey = "mcp-balance-grant")
@OidcClientFilter("mcp-balance-reader")
@Path("/api/v1/delegations/mcp-client/check-balance-grant")
@Consumes(MediaType.APPLICATION_JSON)
interface McpBalanceGrantRestClient {
    @POST
    fun check(request: McpBalanceGrantRequest): Uni<Response>
}

@ApplicationScoped
class McpBalanceGrantClient(@RestClient private val client: McpBalanceGrantRestClient) {
    suspend fun isActive(request: McpBalanceGrantRequest): Boolean = try {
        client.check(request).awaitSuspending().use { it.status == Response.Status.NO_CONTENT.statusCode }
    } catch (e: WebApplicationException) {
        if (e.response?.status != Response.Status.FORBIDDEN.statusCode) throw e
        e.response.close()
        false
    }
}
