// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignNotFoundException
import com.openbank.campaign.application.usecase.CampaignReferenceNotFoundException
import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.domain.model.CampaignDecision
import com.openbank.campaign.domain.model.CampaignDefinition
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.InAppSurface
import com.openbank.campaign.domain.model.IncentiveOfferRef
import com.openbank.campaign.domain.model.MobileDestination
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.StepCondition
import com.openbank.campaign.domain.model.StopCondition
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
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
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of
     * a collection, so `{"steps": [null]}` deserialises happily into a `List<StepRequest>` holding a
     * null. Writing the type honestly is what makes the guard in [toSteps] reachable instead of
     * dead code.
     */
    val steps: List<StepRequest?>,
    val stopCondition: StopConditionRequest? = null,
    /** ADR-0245 D1: a ConversionCatalog key, or absent to measure no conversion. */
    val conversionRule: String? = null,
    /** Percentage assigned to a durable no-contact control cohort, 0..50. Requires conversionRule. */
    val holdoutPercent: Int = 0,
    /** Absent means one-shot: enrolment happens only on POST /{id}/enrol, as it always has. */
    val schedule: ScheduleRequest? = null,
    /**
     * A TriggerCatalog key, or absent. When set, a matching product event enrols a party at once —
     * but only one the segment still contains: the trigger decides when, the segment decides who.
     */
    val trigger: String? = null,
    /** Bounded, explicit yes/no delivery branches. Absent preserves a linear campaign. */
    val decisions: List<DecisionRequest?> = emptyList(),
    /** Exact published incentive revision; redemption remains owned by incentive-service. */
    val incentiveOfferRef: IncentiveOfferRefRequest? = null,
)

data class IncentiveOfferRefRequest(val id: UUID, val name: String, val version: Int)

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
    /** Optional explicit earlier source step for a multi-path decision. */
    val conditionSourceOrder: Int? = null,
    /** The B-arm values for a campaign-wide content experiment; absent keeps one shared message. */
    val variantBVariables: Map<String, String>? = null,
    /** Use the catalogue's safe PUSH counterpart only when this EMAIL step lacks email consent. */
    val fallbackToPush: Boolean = false,
    /** Closed in-app destination opened when the customer taps the resulting PUSH notification. */
    val mobileDestination: MobileDestination? = null,
    /** Closed authenticated-app inventory for a BANNER placement; absent preserves HOME_BANNER. */
    val inAppSurface: InAppSurface? = null,
    /** Optional path-experiment treatment for arm B; all three fields preserve the copy-only default. */
    val variantBTemplate: String? = null,
    val variantBChannel: Channel? = null,
    val variantBDelaySeconds: Long? = null,
    /** Direct forward edge in an explicit-decision journey; absent makes this step terminal. */
    val nextStepOrder: Int? = null,
)

/**
 * One named, reviewable decision node. It may read only delivery confirmation, never an inferred
 * open or a marketing conversion; those facts are not available to journey orchestration.
 */
data class DecisionRequest(
    val sourceStepOrder: Int,
    val evaluationDelaySeconds: Long = 0,
    val confirmedStepOrder: Int,
    val notConfirmedStepOrder: Int,
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
 * A top-level extension rather than a method: lifecycle endpoints remain separately authorized,
 * while this keeps identity extraction outside the HTTP adapter's public surface.
 */
private fun JsonWebToken.principalName(): String = name ?: subject ?: "unknown"

/**
 * The only production read of [CreateCampaignRequest.steps]. `IllegalArgumentException` is mapped
 * to 400 by libs-runtime's `CommonExceptionMappers`; no service-local mapper is added (#526).
 */
private fun CreateCampaignRequest.toSteps(): List<CampaignStep> = steps.mapIndexed { index, raw ->
    val step = requireNotNull(raw) { "steps[$index] must not be null" }
    CampaignStep(
        step.order,
        step.template,
        step.channel,
        step.variables,
        step.delaySeconds,
        step.condition,
        step.conditionSourceOrder,
        step.variantBVariables,
        step.fallbackToPush,
        step.mobileDestination,
        step.inAppSurface,
        step.variantBTemplate,
        step.variantBChannel,
        step.variantBDelaySeconds,
        step.nextStepOrder,
    )
}

/** The only production read of [CreateCampaignRequest.decisions]; same 400 mapping as [toSteps]. */
private fun CreateCampaignRequest.toDecisions(): List<CampaignDecision> = decisions.mapIndexed { index, raw ->
    val decision = requireNotNull(raw) { "decisions[$index] must not be null" }
    CampaignDecision(
        sourceStepOrder = decision.sourceStepOrder,
        evaluationDelaySeconds = decision.evaluationDelaySeconds,
        confirmedStepOrder = decision.confirmedStepOrder,
        notConfirmedStepOrder = decision.notConfirmedStepOrder,
    )
}

private fun CreateCampaignRequest.toDefinition(): CampaignDefinition = CampaignDefinition(
    name = name,
    goal = goal,
    segmentRef = SegmentRef(segmentName, segmentVersion),
    steps = toSteps(),
    stopCondition = stopCondition?.let { StopCondition(it.maxSendsPerParty) },
    conversionRule = conversionRule,
    holdoutPercent = holdoutPercent,
    schedule = schedule?.let { CampaignSchedule(it.cadence, it.endAt) },
    trigger = trigger,
    decisions = toDecisions(),
    incentiveOfferRef = incentiveOfferRef?.let { IncentiveOfferRef(it.id, it.name, it.version) },
)

/**
 * Operator API for the campaign first slice (ADR-0200). Activation is four-eyes gated by the
 * `campaign.activate` action (rules.yaml four_eyes.actions) and re-asserted by the domain
 * maker/checker invariant — the REST layer renders capability, policy decides it.
 */
@Path("/api/v1/campaigns")
@ApplicationScoped
@Suppress("TooManyFunctions") // Each method is a separately authorised lifecycle endpoint.
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
    suspend fun create(request: CreateCampaignRequest): Response = try {
        val createdBy = jwt.principalName()
        val campaign = service.createDraft(
            request.name,
            request.goal,
            SegmentRef(request.segmentName, request.segmentVersion),
            request.toSteps(),
            createdBy,
            request.stopCondition?.let { StopCondition(it.maxSendsPerParty) },
            request.conversionRule,
            request.holdoutPercent,
            request.schedule?.let { CampaignSchedule(it.cadence, it.endAt) },
            request.trigger,
            request.toDecisions(),
            request.incentiveOfferRef?.let { IncentiveOfferRef(it.id, it.name, it.version) },
        )
        Response.status(Response.Status.CREATED).entity(campaign).build()
    } catch (e: CampaignReferenceNotFoundException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }

    /** The authenticated maker may revise only the unsubmitted definition. */
    @PUT
    @Path("/{id}")
    @Authorize(action = "campaign.create", resource = "#id")
    suspend fun revise(@PathParam("id") id: UUID, request: CreateCampaignRequest): Response = runCatching {
        Response.ok(
            service.reviseDraft(
                id = id,
                definition = request.toDefinition(),
                revisedBy = jwt.principalName(),
            ),
        ).build()
    }.getOrElse { Response.status(Response.Status.CONFLICT).entity(mapOf("error" to it.message)).build() }

    /**
     * Starts a new, maker-owned DRAFT from an existing campaign definition. The source is never
     * edited or reactivated: authoring continues in Studio, where the marketer can review every
     * copied surface and entry setting before a separate approver sees the new campaign.
     */
    @POST
    @Path("/{id}/duplicate")
    @Authorize(action = "campaign.create", resource = "#id")
    suspend fun duplicate(@PathParam("id") id: UUID): Response = try {
        Response.status(Response.Status.CREATED).entity(service.duplicateAsDraft(id, jwt.principalName())).build()
    } catch (_: CampaignNotFoundException) {
        Response.status(Response.Status.NOT_FOUND).build()
    } catch (e: NoSuchElementException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    } catch (e: IllegalArgumentException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
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
