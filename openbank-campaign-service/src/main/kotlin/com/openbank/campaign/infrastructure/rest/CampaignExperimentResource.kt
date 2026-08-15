// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignExperimentQuery
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

/** The cohort comparison for a campaign that explicitly opted into a holdout experiment. */
@Path("/api/v1/campaigns")
@ApplicationScoped
class CampaignExperimentResource(private val query: CampaignExperimentQuery) {

    @GET
    @Path("/{id}/experiment")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun experiment(@PathParam("id") id: UUID): Response = try {
        query.summary(id)
            ?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.NOT_FOUND).build()
    } catch (e: IllegalStateException) {
        // Only the explicitly unconfigured experiment is a conflict. A database/query failure must
        // still surface through the normal error path rather than masquerading as a product state.
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }
}
