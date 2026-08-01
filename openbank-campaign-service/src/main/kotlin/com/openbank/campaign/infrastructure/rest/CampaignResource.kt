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

/**
 * Accepted and ignored. Kept so an existing caller that still posts `{"approver": "..."}` does not
 * break — removing a required body would be a breaking contract change, and under ADR-0048 a major
 * bump means moving every path to `/api/v2`, which is out of all proportion to deleting a field
 * nothing reads. The value is never looked at; the approver comes from the token.
 */
data class ApprovalRequest(val approver: String? = null)

/**
 * The authenticated caller — recorded as the maker on create and as the checker on activate.
 *
 * A top-level extension rather than a method: `CampaignResource` sits exactly at detekt's
 * `TooManyFunctions` threshold of 11, which fires AT the limit, so a private helper costs the gate.
 */
private fun JsonWebToken.principalName(): String = name ?: subject ?: "unknown"

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
        val createdBy = jwt.principalName()
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
     * ADR-0200 D5 / ADR-0221 D2: `campaign.activate` is a rules.yaml four_eyes action, and the
     * domain re-asserts maker != checker.
     *
     * The approver is the authenticated caller — it used to arrive in the request body, which made
     * the maker/checker check compare a stored field against a string the same caller supplied.
     * One operator could create a campaign and activate it by sending any other name (#3051). Taken
     * from the token, `approver != createdBy` is a comparison the caller does not control both
     * sides of, which is the only version of that check worth having.
     */
    @POST
    @Path("/{id}/activate")
    @Authorize(action = "campaign.activate", resource = "#id")
    suspend fun activate(@PathParam("id") id: UUID, @Suppress("UNUSED_PARAMETER") ignored: ApprovalRequest?): Response =
        runCatching { Response.ok(service.activate(id, jwt.principalName())).build() }
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
    suspend fun enrol(@PathParam("id") id: UUID): Response = runCatching {
        val outcome = service.enrol(id)
        Response.ok(mapOf("enrolled" to outcome.enrolled, "failed" to outcome.failed)).build()
    }.getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    @GET
    @Path("/{id}/enrolments")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun enrolments(@PathParam("id") id: UUID): Response = Response.ok(service.listEnrolments(id)).build()
}
