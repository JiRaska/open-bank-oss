// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.infrastructure.persistence.repository.McpOAuthHandoffStore
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

@Path("/api/v1/delegations/mcp-connect/native-handoffs/issuer")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class McpNativeHandoffIssuerResource(
    private val settings: McpNativeHandoffSettings,
    private val store: McpOAuthHandoffStore,
    private val identity: SecurityIdentity,
) {
    data class BindingRequest(val tokenSha256: String, val binding: McpOAuthHandoffStore.Binding)

    data class Attestation(
        val tokenSha256: String,
        val binding: McpOAuthHandoffStore.Binding,
        val contextSha256: String,
    )

    data class ClaimedParty(val partyId: UUID)

    data class ConsumedProof(val partyId: UUID, val keycloakSubject: String, val contextSha256: String)

    @POST
    @Path("/issue")
    @Authorize(action = "delegation.mcp-connect.issuer.native-issue")
    suspend fun issue(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: String?,
        body: BindingRequest?,
    ): Response {
        requireIssuer(party)
        val request = requireNotNull(body) { "native handoff binding is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, request.tokenSha256)
        if (!store.issue(request.tokenSha256, request.binding)) throw WebApplicationException(409)
        return Response.noContent().build()
    }

    @POST
    @Path("/claimed-party")
    @Authorize(action = "delegation.mcp-connect.issuer.native-claimed-party")
    suspend fun claimedParty(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: String?,
        body: BindingRequest?,
    ): Response {
        requireIssuer(party)
        val request = requireNotNull(body) { "native handoff binding is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, request.tokenSha256)
        val claimed = store.claimedParty(request.tokenSha256, request.binding) ?: return Response.noContent().build()
        return Response.ok(ClaimedParty(claimed)).build()
    }

    @POST
    @Path("/attest-selection")
    @Authorize(action = "delegation.mcp-connect.issuer.native-attest-selection")
    suspend fun attestSelection(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: String?,
        body: Attestation?,
    ): Response {
        requireIssuer(party)
        val request = requireNotNull(body) { "native handoff attestation is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, request.tokenSha256)
        if (!store.attestSelectedContext(request.tokenSha256, request.binding, request.contextSha256)) {
            throw WebApplicationException(409)
        }
        return Response.noContent().build()
    }

    @POST
    @Path("/consume")
    @Authorize(action = "delegation.mcp-connect.issuer.native-consume")
    suspend fun consume(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) party: String?,
        body: BindingRequest?,
    ): Response {
        requireIssuer(party)
        val request = requireNotNull(body) { "native handoff binding is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, request.tokenSha256)
        val proof = store.consume(request.tokenSha256, request.binding) ?: return Response.noContent().build()
        val receipt = ConsumedProof(proof.identity.partyId, proof.identity.keycloakSubject, proof.contextSha256)
        return Response.ok(receipt).build()
    }

    private fun requireIssuer(party: String?) {
        settings.requireEnabled()
        if (party != null || ServiceAccountIdentity.verifiedClientId(identity) != "openbank-keycloak-mcp") {
            throw ForbiddenException("native handoff issuance is reserved to Keycloak")
        }
    }
}

@ApplicationScoped
class McpNativeHandoffSettings(
    @ConfigProperty(name = "openbank.delegation.mcp-connect-enabled", defaultValue = "false")
    private val connectEnabled: Boolean,
    @ConfigProperty(name = "openbank.delegation.mcp-native-handoff-enabled", defaultValue = "false")
    private val handoffEnabled: Boolean,
) {
    fun requireEnabled() {
        if (!connectEnabled || !handoffEnabled) throw NotFoundException()
    }
}
