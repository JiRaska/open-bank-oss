// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.InAppSurface
import com.openbank.campaign.domain.model.TemplateCatalog
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/** A reviewed content choice the Campaign Studio may offer for one journey step. */
data class CampaignTemplateSummary(
    val template: String,
    val channel: Channel,
    val variables: List<String>,
    val inAppSurface: InAppSurface? = null,
)

/**
 * The content catalogue for Campaign Studio.
 *
 * Templates are deliberately served from the same closed catalogue the aggregate validates.  A
 * front-end copy inevitably drifts: a newly approved app surface either remains invisible to a
 * marketer or appears as a stale choice the server no longer accepts.  This endpoint is read-only;
 * adding a template remains reviewed code, never a browser-authored message or markup.
 */
@Path("/api/v1/campaigns/templates")
@ApplicationScoped
class CampaignTemplateCatalogResource {

    @GET
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun templates(): Response = Response.ok(
        TemplateCatalog.ALL.keys.sorted().map { template ->
            CampaignTemplateSummary(
                template = template,
                channel = checkNotNull(TemplateCatalog.CHANNEL_OF[template]),
                variables = TemplateCatalog.ALL.getValue(template).sorted(),
                inAppSurface = TemplateCatalog.inAppSurfaceFor(template),
            )
        },
    ).build()
}
