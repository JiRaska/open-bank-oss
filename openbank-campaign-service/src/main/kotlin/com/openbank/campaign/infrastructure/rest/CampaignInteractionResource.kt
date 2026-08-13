// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignInteractionQuery
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * Internal, data-minimising ownership check used by customer-edge before accepting an app event.
 *
 * A 404 covers every invalid case (unknown id, another party, email, or a request that never made
 * the notification handoff). Returning a common result prevents the route becoming an oracle for
 * campaign activity. The edge turns that 404 into its public generic 400 response.
 */
@Path("/api/v1/campaigns/interactions")
@ApplicationScoped
class CampaignInteractionResource(private val query: CampaignInteractionQuery) {

    @GET
    @Path("/{interactionRef}")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "campaign.interaction.validate", resource = "#partyId")
    suspend fun validate(
        @PathParam("interactionRef") interactionRef: UUID,
        @HeaderParam("X-Customer-Party-Id") partyId: String?,
    ): Response {
        val customerPartyId = partyId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Response.status(Response.Status.BAD_REQUEST).build()
        val attribution = query.resolve(interactionRef, customerPartyId)
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(
            CampaignInteractionAttributionResponse(
                campaignId = attribution.campaignId,
                stepOrder = attribution.stepOrder,
                channel = attribution.channel.name,
            ),
        ).build()
    }
}

data class CampaignInteractionAttributionResponse(val campaignId: UUID, val stepOrder: Int, val channel: String)
