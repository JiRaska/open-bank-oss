// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.BulkAdmissionService
import com.openbank.campaign.application.usecase.CampaignNotFoundException
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/** Operator control for bounded bulk admission. Campaign approval still precedes every run. */
@ApplicationScoped
@Path("/api/v1/campaigns/{id}/bulk-runs")
class BulkRunResource(private val admission: BulkAdmissionService, private val jwt: JsonWebToken) {
    @POST
    @Authorize(action = "campaign.enrol", resource = "#id")
    suspend fun start(@PathParam("id") id: UUID): Response = try {
        Response.status(Response.Status.CREATED)
            .entity(admission.start(id, jwt.name ?: jwt.subject ?: "unknown"))
            .build()
    } catch (_: CampaignNotFoundException) {
        Response.status(Response.Status.NOT_FOUND).build()
    } catch (e: IllegalArgumentException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    } catch (e: IllegalStateException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }

    @GET
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun list(@PathParam("id") id: UUID): Response = Response.ok(admission.list(id)).build()

    @GET
    @Path("/{runId}")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID, @PathParam("runId") runId: UUID): Response {
        val run = admission.find(runId)?.takeIf { it.campaignId == id }
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(run).build()
    }

    @POST
    @Path("/{runId}/resume")
    @Authorize(action = "campaign.resume", resource = "#id")
    suspend fun resume(@PathParam("id") id: UUID, @PathParam("runId") runId: UUID): Response = try {
        val run = admission.find(runId)?.takeIf { it.campaignId == id }
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        Response.ok(admission.resume(run.id, jwt.name ?: jwt.subject ?: "unknown")).build()
    } catch (_: CampaignNotFoundException) {
        Response.status(Response.Status.NOT_FOUND).build()
    } catch (e: IllegalStateException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }
}
