// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

data class CreateCampaignRequest(
    val name: String,
    val goal: String,
    val segmentName: String,
    val segmentVersion: Int,
    val steps: List<StepRequest>,
)

data class StepRequest(
    val order: Int,
    val template: String,
    val variables: Map<String, String> = emptyMap(),
    val delaySeconds: Long = 0,
)

data class ApprovalRequest(val approver: String)

/**
 * Operator API for the campaign first slice (ADR-0200). Activation is four-eyes gated by the
 * `campaign.activate` action (rules.yaml four_eyes.actions) and re-asserted by the domain
 * maker/checker invariant — the REST layer renders capability, policy decides it.
 */
@Path("/api/v1/campaigns")
@ApplicationScoped
class CampaignResource(private val service: CampaignService, private val jwt: JsonWebToken) {

    @GET
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun list(): Response = Response.ok(service.list()).build()

    @GET
    @Path("/{id}")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): Response =
        service.get(id)?.let { Response.ok(it).build() } ?: Response.status(Response.Status.NOT_FOUND).build()

    @POST
    @Authorize(action = "campaign.create", resource = "#request.name")
    suspend fun create(request: CreateCampaignRequest): Response {
        val createdBy = jwt.name ?: jwt.subject ?: "unknown"
        val steps = request.steps.map {
            CampaignStep(it.order, it.template, Channel.EMAIL, it.variables, it.delaySeconds)
        }
        val campaign = service.createDraft(
            request.name,
            request.goal,
            SegmentRef(request.segmentName, request.segmentVersion),
            steps,
            createdBy,
        )
        return Response.status(Response.Status.CREATED).entity(campaign).build()
    }

    @POST
    @Path("/{id}/submit")
    @Authorize(action = "campaign.submit", resource = "#id")
    suspend fun submit(@PathParam("id") id: UUID): Response = runCatching { Response.ok(service.submit(id)).build() }
        .getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    /**
     * ADR-0200 D5: `campaign.activate` is a rules.yaml four_eyes action — this endpoint only runs
     * after the approval flow completes; the domain re-asserts maker != checker.
     */
    @POST
    @Path("/{id}/activate")
    @Authorize(action = "campaign.activate", resource = "#id")
    suspend fun activate(@PathParam("id") id: UUID, request: ApprovalRequest): Response =
        runCatching { Response.ok(service.activate(id, request.approver)).build() }
            .getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    @POST
    @Path("/{id}/pause")
    @Authorize(action = "campaign.pause", resource = "#id")
    suspend fun pause(@PathParam("id") id: UUID): Response = runCatching { Response.ok(service.pause(id)).build() }
        .getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    @POST
    @Path("/{id}/resume")
    @Authorize(action = "campaign.resume", resource = "#id")
    suspend fun resume(@PathParam("id") id: UUID): Response = runCatching { Response.ok(service.resume(id)).build() }
        .getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    @POST
    @Path("/{id}/close")
    @Authorize(action = "campaign.close", resource = "#id")
    suspend fun close(@PathParam("id") id: UUID): Response = runCatching { Response.ok(service.close(id)).build() }
        .getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    @POST
    @Path("/{id}/enrol")
    @Authorize(action = "campaign.enrol", resource = "#id")
    suspend fun enrol(@PathParam("id") id: UUID): Response =
        runCatching { Response.ok(mapOf("enrolled" to service.enrol(id))).build() }
            .getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    @GET
    @Path("/{id}/enrolments")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun enrolments(@PathParam("id") id: UUID): Response = Response.ok(service.listEnrolments(id)).build()
}
