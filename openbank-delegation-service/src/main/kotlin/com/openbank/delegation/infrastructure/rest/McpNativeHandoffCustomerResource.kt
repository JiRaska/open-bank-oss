// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.infrastructure.persistence.repository.McpOAuthHandoffStore
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/delegations/mcp-connect/customer/native-handoffs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR")
class McpNativeHandoffCustomerResource(
    private val settings: McpNativeHandoffSettings,
    private val customerAccess: McpConnectCustomerAccess,
    private val store: McpOAuthHandoffStore,
) {
    data class ClaimRequest(val tokenSha256: String, val keycloakSubject: String)

    data class ClaimReceipt(val requestRef: String)

    data class SelectedBindingRequest(val keycloakSubject: String)

    data class ProofRequest(val keycloakSubject: String, val contextSha256: String)

    data class Selection(
        val requestRef: String,
        val contextSha256: String,
        val clientId: String,
        val parBinding: String,
        val parJkt: String?,
        val expiresAtEpochSecond: Long,
    )

    @POST
    @Path("/claim")
    @Authorize(action = "delegation.mcp-connect.customer.native-claim")
    suspend fun claim(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: UUID?,
        body: ClaimRequest?,
    ): ClaimReceipt {
        val owner = requireCustomer(party)
        val request = requireNotNull(body) { "native handoff claim is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, request.tokenSha256)
        val claimant = McpOAuthHandoffStore.Claim(owner, request.keycloakSubject)
        val reference = store.claimAndRegister(request.tokenSha256, claimant) ?: throw NotFoundException()
        return ClaimReceipt(reference)
    }

    @POST
    @Path("/{requestRef}/selected-binding")
    @Authorize(action = "delegation.mcp-connect.customer.native-selected-binding")
    suspend fun selectedBinding(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: UUID?,
        @PathParam("requestRef") requestRef: String,
        body: SelectedBindingRequest?,
    ): Selection {
        val owner = requireCustomer(party)
        val request = requireNotNull(body) { "native handoff owner subject is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, requestRef)
        val binding = store.selectedBinding(requestRef, McpOAuthHandoffStore.Claim(owner, request.keycloakSubject))
            ?: throw NotFoundException()
        val mode = when (binding.kind) {
            McpOAuthHandoffStore.BindingKind.HOSTED -> "confidential_client"
            McpOAuthHandoffStore.BindingKind.DPOP -> "dpop"
        }
        return Selection(
            binding.requestRef,
            binding.contextSha256,
            binding.clientId,
            mode,
            binding.parJkt,
            binding.expiresAtEpochSecond,
        )
    }

    @POST
    @Path("/{requestRef}/mark-verified")
    @Authorize(action = "delegation.mcp-connect.customer.native-mark-verified")
    suspend fun markVerified(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: UUID?,
        @PathParam("requestRef") requestRef: String,
        body: ProofRequest?,
    ): Response {
        val owner = requireCustomer(party)
        val request = requireNotNull(body) { "native handoff proof is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, requestRef)
        val claimant = McpOAuthHandoffStore.Claim(owner, request.keycloakSubject)
        if (!store.markProven(requestRef, claimant, request.contextSha256)) throw NotFoundException()
        return Response.noContent().build()
    }

    private fun requireCustomer(party: UUID?): UUID {
        settings.requireEnabled()
        return customerAccess.requireCustomer(party)
    }
}
