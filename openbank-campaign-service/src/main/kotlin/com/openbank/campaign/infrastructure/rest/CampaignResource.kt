// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.StepCondition
import com.openbank.campaign.domain.model.StopCondition
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant
import java.util.UUID

data class CreateCampaignRequest(
    val name: String,
    val goal: String,
    val segmentName: String,
    val segmentVersion: Int,
    val steps: List<StepRequest>,
    val stopCondition: StopConditionRequest? = null,
    /** ADR-0245 D1: a ConversionCatalog key, or absent to measure no conversion. */
    val conversionRule: String? = null,
    /** Absent means one-shot: enrolment happens only on POST /{id}/enrol, as it always has. */
    val schedule: ScheduleRequest? = null,
    /**
     * A TriggerCatalog key, or absent. When set, a matching product event enrols a party at once —
     * but only one the segment still contains: the trigger decides when, the segment decides who.
     */
    val trigger: String? = null,
)

/** Optional on create (ADR-0200 D1, #3585): absent means the journey runs every step, as before. */
data class StopConditionRequest(val maxSendsPerParty: Int)

/**
 * A recurring campaign's cadence.
 *
 * [cadence] is a `ScheduleCatalog` key, never a cron expression — the expression and its time zone
 * live in domain code, so a malformed one cannot be posted and a campaign cannot quietly acquire a
 * schedule that never fires. `GET /api/v1/campaigns/cadences` lists what may be sent here.
 *
 * The schedule is stored on the draft and only becomes a live Temporal schedule at activation, so a
 * campaign cannot enrol anyone before it has passed four-eyes.
 */
data class ScheduleRequest(val cadence: String, val endAt: Instant? = null)

/**
 * A step on the create body.
 *
 * [channel] defaults to EMAIL so every body written before PUSH existed keeps its meaning. It was
 * absent entirely until #3584's PUSH support was reachable only from the domain: the resource
 * hardcoded `Channel.EMAIL`, so the openapi enum documented a value no caller could ever select.
 * Validation is the [CampaignStep] init invariant — the template's catalogue channel must agree
 * with the step channel — not a second edge-level check that could drift from it.
 */
data class StepRequest(
    val order: Int,
    val template: String,
    val channel: Channel = Channel.EMAIL,
    val variables: Map<String, String> = emptyMap(),
    val delaySeconds: Long = 0,
    /** Optional branch condition (ADR-0200 D1, #3585). Absent means the step always runs. */
    val condition: StepCondition? = null,
)

/**
 * The old activate body. Retained as a type only so the OpenAPI schema can keep documenting it as
 * accepted-and-ignored: a client still posting `{"approver": "..."}` should know it is harmless.
 *
 * `activate` declares **no entity parameter at all**, deliberately. Keeping one — even nullable,
 * even with `@Consumes(WILDCARD)` — makes RESTEasy look for a reader that can turn the request's
 * media type into this class, so a caller sending `text/plain` (RestAssured's default for a
 * bodyless POST) gets 415 while one sending no Content-Type at all (curl's default) succeeds. A
 * method with no entity parameter never reads the body, so every shape works: none, empty, or
 * legacy JSON. That asymmetry is why the first fix passed a manual curl check and still failed
 * CampaignRestContractIT.
 *
 * The URL major is deliberately not spelled out here: the api-contract gate derives "the newest
 * served URL major" by matching that text in the source, so a comment explaining the rule is read
 * as an endpoint implementing it, and the gate fails on prose (#3119).
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
            CampaignStep(it.order, it.template, it.channel, it.variables, it.delaySeconds, it.condition)
        }
        val campaign = service.createDraft(
            request.name,
            request.goal,
            SegmentRef(request.segmentName, request.segmentVersion),
            steps,
            createdBy,
            request.stopCondition?.let { StopCondition(it.maxSendsPerParty) },
            request.conversionRule,
            request.schedule?.let { CampaignSchedule(it.cadence, it.endAt) },
            request.trigger,
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
    suspend fun activate(@PathParam("id") id: UUID): Response =
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
