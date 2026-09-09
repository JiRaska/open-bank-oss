// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.GetDisclosureUseCase
import com.openbank.delegation.application.port.`in`.IssueDisclosureRedemptionCommand
import com.openbank.delegation.application.port.`in`.IssueDisclosureRedemptionUseCase
import com.openbank.delegation.application.port.`in`.PrepareDisclosureCommand
import com.openbank.delegation.application.port.`in`.PrepareDisclosureUseCase
import com.openbank.delegation.infrastructure.rest.dto.DisclosureResponse
import com.openbank.delegation.infrastructure.rest.dto.IssueDisclosureRedemptionRequest
import com.openbank.delegation.infrastructure.rest.dto.IssueDisclosureRedemptionResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import java.util.UUID

@Path("/api/v1/disclosures")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class DisclosureResource(
    private val prepareDisclosure: PrepareDisclosureUseCase,
    private val getDisclosure: GetDisclosureUseCase,
    private val redemption: IssueDisclosureRedemptionUseCase,
) {
    @POST
    @Path("/delegations/{delegationId}")
    @Authorize(action = "delegation.disclosure.prepare", resource = "#delegationId")
    suspend fun prepare(
        @PathParam("delegationId") delegationId: UUID,
        @HeaderParam("X-Request-ID") requestId: UUID?,
        @HeaderParam("X-Customer-Party-Id") callerPartyId: UUID?,
        @Context uriInfo: UriInfo,
    ): Response {
        requireNotNull(requestId) { "X-Request-ID header is required" }
        val result = prepareDisclosure.prepare(PrepareDisclosureCommand(requestId, delegationId, callerPartyId))
        return Response.accepted(DisclosureResponse.from(result))
            .location(uriInfo.baseUriBuilder.path("api/v1/disclosures/${result.id}").build()).build()
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.disclosure.read", resource = "#id")
    suspend fun get(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") callerPartyId: UUID?,
    ): DisclosureResponse = DisclosureResponse.from(getDisclosure.get(id, callerPartyId))

    @POST
    @Path("/{id}/redemptions")
    @Authorize(action = "delegation.disclosure.manage", resource = "#id")
    suspend fun issueRedemption(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") callerPartyId: UUID?,
        request: IssueDisclosureRedemptionRequest,
    ): Response {
        val issued = redemption.issue(
            IssueDisclosureRedemptionCommand(id, callerPartyId, request.recipient, request.expiresAt, request.maxViews),
        )
        return Response.status(Response.Status.CREATED).entity(
            IssueDisclosureRedemptionResponse(
                issued.redemptionId,
                issued.magicToken,
                issued.expiresAt,
                issued.maxViews,
            ),
        ).build()
    }

    @DELETE
    @Path("/{id}/redemption")
    @Authorize(action = "delegation.disclosure.manage", resource = "#id")
    suspend fun revokeRedemption(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") callerPartyId: UUID?,
    ): Response {
        redemption.revoke(id, callerPartyId)
        return Response.noContent().build()
    }
}
