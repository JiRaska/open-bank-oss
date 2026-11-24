// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.infrastructure.persistence.repository.McpConnectRequestStore
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

@Path("/api/v1/delegations/mcp-connect/customer/connections")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR")
class McpConnectCustomerConnectionResource(
    private val customerAccess: McpConnectCustomerAccess,
    private val store: McpConnectRequestStore,
) {
    @GET
    @Authorize(action = "delegation.mcp-connect.customer.read")
    suspend fun connections(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Connections = Connections(store.listConnections(customerAccess.requireCustomer(customerPartyId)))

    @POST
    @Path("/{connectionId}/revoke")
    @Authorize(action = "delegation.mcp-connect.customer.revoke")
    suspend fun revoke(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @PathParam("connectionId") connectionId: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val party = customerAccess.requireCustomer(customerPartyId)
        requireMcpConnectIdempotencyKey(idempotencyKey, connectionId.toString())
        if (!store.revoke(party, connectionId)) throw NotFoundException()
        return Response.noContent().build()
    }

    data class Connections(val connections: List<McpConnectRequestStore.Connection>)
}

@ApplicationScoped
class McpConnectCustomerAccess(
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.delegation.mcp-connect-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    fun requireCustomer(customerPartyId: UUID?): UUID {
        if (!enabled) throw NotFoundException()
        if (!ServiceAccountIdentity.isPrincipal(identity, EDGE_PRINCIPAL) || customerPartyId == null) {
            throw ForbiddenException("connect requests are reserved to the verified customer edge")
        }
        return customerPartyId
    }

    private companion object {
        const val EDGE_PRINCIPAL = "service-account-openbank-edge"
    }
}
