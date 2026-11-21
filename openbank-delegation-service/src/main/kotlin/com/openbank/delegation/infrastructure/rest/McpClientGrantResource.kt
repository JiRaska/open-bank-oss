// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.ResolveMcpClientGrantUseCase
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0383 D3 — mcp-service's per-call lookup of the customer's `MCP_CLIENT` grants.
 *
 * Two independent identity checks, because this answer is what decides which of a customer's
 * accounts a third-party client may read: OPA admits only mcp-service's own service-account
 * (`delegation_rest_ext.rego`, reason `mcp-client-grant-resolve`), and this handler refuses every
 * other verified client id in code, so an advisory PDP (test profile, a broken bundle) does not
 * widen it. A request carrying the edge's customer-party header is refused too: this is not a
 * customer route and must never be proxied as one.
 *
 * `@RolesAllowed` is the authentication half: without it an anonymous request reached the handler
 * and was refused 403 by the identity guard, so "no token" and "wrong caller" were the same answer
 * and nothing proved a tokenless call is rejected as unauthenticated. Quarkus now answers 401 before
 * the handler runs. Both mcp realm templates grant `service-account-openbank-mcp` `ROLE_API`.
 * Proven over real HTTP by `McpClientGrantIdentityGuardIT` and the mcp-service pact replay.
 */
@Tag(name = "Delegations")
@Path("/api/v1/delegations/mcp-client")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class McpClientGrantResource(private val resolver: ResolveMcpClientGrantUseCase) {
    @Inject
    lateinit var identity: SecurityIdentity

    @Operation(summary = "Active MCP_CLIENT grants for (grantor party, client id, DPoP key thumbprint)")
    @POST
    @Path("/resolve")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "delegation.mcp-client.resolve")
    suspend fun resolve(
        request: ResolveMcpClientGrantRequest?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): ResolveMcpClientGrantResponse {
        if (customerPartyId != null || !ServiceAccountIdentity.isClient(identity, setOf(MCP_SERVICE_CLIENT))) {
            throw ForbiddenException("MCP client grant resolution is reserved to mcp-service")
        }
        requireNotNull(request) { "request body is required" }
        val grantor = requireNotNull(request.grantorPartyId) { "grantorPartyId is required" }
        val clientId = request.clientId
        require(!clientId.isNullOrBlank() && clientId.length <= DelegationGrant.CLIENT_ID_MAX_LENGTH) {
            "clientId is required and at most ${DelegationGrant.CLIENT_ID_MAX_LENGTH} characters"
        }
        val jkt = request.keyThumbprint
        require(jkt != null && JKT.matches(jkt)) { "keyThumbprint must be an RFC 7638 SHA-256 thumbprint" }
        return ResolveMcpClientGrantResponse(resolver.resolve(grantor, clientId, jkt).map(McpClientGrantDto::from))
    }

    private companion object {
        /** mcp-service's M2M client (`quarkus.oidc-client.m2m`), service-account-openbank-mcp. */
        const val MCP_SERVICE_CLIENT = "openbank-mcp"
        val JKT = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

data class ResolveMcpClientGrantRequest(
    val grantorPartyId: UUID? = null,
    val clientId: String? = null,
    val keyThumbprint: String? = null,
)

/** Never the grant itself: no names, limits, approval policy or key thumbprint leave the service. */
data class ResolveMcpClientGrantResponse(val grants: List<McpClientGrantDto>)

data class McpClientGrantDto(
    val grantId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val validTo: OffsetDateTime?,
) {
    companion object {
        fun from(g: DelegationGrant) = McpClientGrantDto(g.id, g.resourceType, g.resourceId, g.capabilities, g.validTo)
    }
}
