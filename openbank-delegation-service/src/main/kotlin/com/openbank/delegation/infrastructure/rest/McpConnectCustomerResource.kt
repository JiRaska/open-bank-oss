// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.infrastructure.persistence.repository.McpConnectRequestStore
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

@Path("/api/v1/delegations/mcp-connect/customer")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR")
class McpConnectCustomerResource(
    private val identity: SecurityIdentity,
    private val store: McpConnectRequestStore,
    private val ownership: ResourceOwnershipClient,
    @ConfigProperty(name = "openbank.delegation.mcp-connect-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    @GET
    @Path("/requests")
    @Authorize(action = "delegation.mcp-connect.customer.read")
    suspend fun list(@HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?): Reviews =
        Reviews(store.listForParty(requireCustomer(customerPartyId)).map(Review::from))

    @GET
    @Path("/requests/{requestRef}")
    @Authorize(action = "delegation.mcp-connect.customer.read")
    suspend fun get(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @PathParam("requestRef") requestRef: String,
    ): Review = Review.from(find(requireCustomer(customerPartyId), requestRef))

    @PUT
    @Path("/requests/{requestRef}/selection")
    @Authorize(action = "delegation.mcp-connect.customer.select")
    suspend fun select(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @PathParam("requestRef") requestRef: String,
        selection: Selection?,
    ): Review {
        val party = requireCustomer(customerPartyId)
        find(party, requestRef)
        val accounts = requireNotNull(selection) { "selection is required" }.accounts.mapIndexed { index, account ->
            requireNotNull(account) { "accounts[$index] must not be null" }
        }
        require(accounts.size in 1..MAX_ACCOUNTS && accounts.map { it.accountId }.toSet().size == accounts.size) {
            "selection must contain distinct accounts"
        }
        for (account in accounts) {
            require(account.capabilities.isNotEmpty() && allowedCapabilities.containsAll(account.capabilities)) {
                "unsupported account capability"
            }
            val verdict = ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, account.accountId)
            if (verdict != OwnershipVerdict.OWNED) {
                throw ForbiddenException("selected account ownership could not be established")
            }
        }
        if (!store.select(party, requestRef, accounts)) {
            throw ClientErrorException("selection is unavailable", Response.Status.CONFLICT)
        }
        return Review.from(find(party, requestRef))
    }

    @GET
    @Path("/connections")
    @Authorize(action = "delegation.mcp-connect.customer.read")
    suspend fun connections(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Connections = Connections(store.listConnections(requireCustomer(customerPartyId)))

    @POST
    @Path("/connections/{connectionId}/revoke")
    @Authorize(action = "delegation.mcp-connect.customer.revoke")
    suspend fun revoke(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @PathParam("connectionId") connectionId: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val party = requireCustomer(customerPartyId)
        requireMcpConnectIdempotencyKey(idempotencyKey, connectionId.toString())
        if (!store.revoke(party, connectionId)) throw NotFoundException()
        return Response.noContent().build()
    }

    private suspend fun find(party: UUID, requestRef: String): McpConnectRequestStore.Review {
        if (!REQUEST_REF.matches(requestRef)) throw NotFoundException()
        return store.findForParty(party, requestRef) ?: throw NotFoundException()
    }

    private fun requireCustomer(customerPartyId: UUID?): UUID {
        if (!enabled) throw NotFoundException()
        if (!ServiceAccountIdentity.isPrincipal(identity, EDGE_PRINCIPAL) || customerPartyId == null) {
            throw ForbiddenException("connect requests are reserved to the verified customer edge")
        }
        return customerPartyId
    }

    data class Selection(val accounts: List<McpConnectRequestStore.AccountSelection?>)
    data class Reviews(val requests: List<Review>)
    data class Connections(val connections: List<McpConnectRequestStore.Connection>)
    data class Review(
        val requestRef: String,
        val partyId: UUID,
        val clientId: String,
        val redirectHost: String,
        val resource: String,
        val scopes: Set<String>,
        val parJkt: String?,
        val expiresAtEpochSecond: Long,
        val status: String,
        val accounts: List<McpConnectRequestStore.AccountSelection>,
        val challengeId: UUID?,
        val approvalRequestId: String?,
        val contextSha256: String?,
    ) {
        companion object {
            fun from(review: McpConnectRequestStore.Review): Review = with(review) {
                Review(
                    request.requestRef,
                    request.partyId,
                    request.clientId,
                    request.redirectHost,
                    request.resource,
                    request.scopes,
                    request.parJkt,
                    request.expiresAt.toEpochSecond(),
                    when {
                        connectionId != null -> "ACTIVE"
                        challengeId != null -> "CHALLENGE_BOUND"
                        accounts.isNotEmpty() -> "SELECTED"
                        else -> "PENDING_SELECTION"
                    },
                    accounts,
                    challengeId,
                    challengeId?.let { "mcp-connect:${request.requestRef}" },
                    contextSha256,
                )
            }
        }
    }

    private companion object {
        const val MAX_ACCOUNTS = 20
        const val EDGE_PRINCIPAL = "service-account-openbank-edge"
        val REQUEST_REF = Regex("^[0-9a-f]{64}$")
        val allowedCapabilities = DelegationGrant.MCP_CLIENT_PERMITTED_CAPABILITIES
            .map(DelegationCapability::name).toSet()
    }
}
