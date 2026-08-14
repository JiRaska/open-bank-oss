// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignPlanningQuery
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/**
 * Read-only campaign planning view. The literal segment takes precedence over the campaign id
 * template, and uses a collection-level authorisation scope because there is no single campaign id.
 */
@Path("/api/v1/campaigns/planning")
@ApplicationScoped
class CampaignPlanningResource(private val planning: CampaignPlanningQuery) {

    @GET
    @Authorize(action = "campaign.read", resource = "")
    suspend fun planning(): Response = Response.ok(planning.items()).build()
}
