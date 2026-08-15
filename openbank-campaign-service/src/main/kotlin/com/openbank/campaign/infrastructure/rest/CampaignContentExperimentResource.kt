// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignContentExperimentQuery
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

/** The conversion comparison of a campaign explicitly configured with A/B message content. */
@Path("/api/v1/campaigns")
@ApplicationScoped
class CampaignContentExperimentResource(private val query: CampaignContentExperimentQuery) {

    @GET
    @Path("/{id}/content-experiment")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun contentExperiment(@PathParam("id") id: UUID): Response = try {
        query.summary(id)
            ?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.NOT_FOUND).build()
    } catch (e: IllegalStateException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }
}
