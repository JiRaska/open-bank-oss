// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.onboarding.ChooseStrategyCommand
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.StartOnboardingCommand
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.onboarding.CedingContract
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.authz.ContractAccessGuard.Companion.PARTY_HEADER
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.util.UUID

/**
 * The participant's digital onboarding (ADR-0334 slice S2): start, questionnaire, recommendation,
 * strategy + key-information document, acceptance, SCA signature, status, withdrawal.
 *
 * ## Caller and ownership
 * Only the customer edge (`ROLE_API`) calls these routes. The acting party is resolved by S1's
 * shared [ContractAccessGuard]: `X-Customer-Party-Id` is trusted only from the edge relay that
 * verified the customer's token, never from a body or any other principal. Every lookup is scoped to that party: an
 * application of another party answers 404, exactly like an id that does not exist, so the routes
 * cannot be used to probe for other participants' applications. Operators use
 * `PensionOperatorResource`, never these routes.
 *
 * `@Path` sits directly above `class` (#3371); absent parameters are nullable and checked (#3104).
 */
@Tag(name = "Pension onboarding", description = "Digital onboarding of a new pension contract or a transfer-in")
@Path("/api/v1/pension/onboarding/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API)
class OnboardingResource {

    @Inject
    lateinit var guard: ContractAccessGuard

    /** The acting participant, vouched for by the edge relay (ContractAccessGuard). */
    private fun partyOf(header: String?): UUID = checkNotNull(guard.actingParticipant(header).customerPartyId)

    @Inject
    lateinit var onboarding: OnboardingService

    @POST
    @Operation(
        summary = "Start an application; eligibility and KYC are checked at once (REJECTED with reasons if not met)",
    )
    @Authorize(action = "pension.onboarding.start")
    suspend fun start(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: StartApplicationRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val partyId = partyOf(party)
        val body = requireNotNull(request) { "request body is required" }
        val schedule = requireNotNull(body.schedule) { "schedule is required" }
        val kind = body.kind ?: OnboardingKind.NEW_CONTRACT
        val ceding = body.transferIn?.let {
            CedingContract(
                providerId = requireNotNull(it.providerId) { "transferIn.providerId is required" },
                providerName = requireNotNull(it.providerName) { "transferIn.providerName is required" },
                contractNumber = requireNotNull(it.contractNumber) { "transferIn.contractNumber is required" },
            )
        }
        require((kind == OnboardingKind.TRANSFER_IN) == (ceding != null)) {
            "transferIn is required for, and only for, kind TRANSFER_IN"
        }
        val application = onboarding.start(
            StartOnboardingCommand(
                actingPartyId = partyId,
                onBehalfOfPartyId = body.onBehalfOfPartyId,
                kind = kind,
                productLine = requireNotNull(body.productLine) { "productLine is required" },
                jurisdiction = requireNotNull(body.jurisdiction) { "jurisdiction is required" },
                providerEntityId = requireNotNull(body.providerEntityId) { "providerEntityId is required" },
                providerType = requireNotNull(body.providerType) { "providerType is required" },
                schedule = ContributionSchedule(
                    amount = requireNotNull(schedule.amount) { "schedule.amount is required" },
                    currency = requireNotNull(schedule.currency) { "schedule.currency is required" },
                    frequency = requireNotNull(schedule.frequency) { "schedule.frequency is required" },
                    employerAmount = schedule.employerAmount ?: BigDecimal.ZERO,
                ),
                declaredBirthDate = requireNotNull(body.birthDate) { "birthDate is required" },
                declaredResidencyCountry = body.residencyCountry,
                residencyEvidence = body.residencyEvidence.orEmpty().mapIndexed { i, e ->
                    requireNotNull(e) { "residencyEvidence[$i] must not be null" }
                }.toSet(),
                ceding = ceding,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(ApplicationResponse.from(application)).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Status of one of the caller's applications")
    @Authorize(action = "pension.onboarding.inspect", resource = "#id")
    suspend fun get(@HeaderParam(PARTY_HEADER) party: String?, @PathParam("id") id: UUID): ApplicationResponse =
        ApplicationResponse.from(onboarding.get(id, partyOf(party)))

    @POST
    @Path("/{id}/questionnaire")
    @Operation(summary = "Answer the suitability / appropriateness / ESG questionnaire; returns the recommendation")
    @Authorize(action = "pension.onboarding.questionnaire", resource = "#id")
    suspend fun questionnaire(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
        request: QuestionnaireRequest?,
    ): QuestionnaireResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val answers = QuestionnaireAnswers(
            knowledgeLevel = body.knowledgeLevel,
            experienceLevel = body.experienceLevel,
            riskAppetite = requireNotNull(body.riskAppetite) { "riskAppetite is required" },
            lossTolerance = requireNotNull(body.lossTolerance) { "lossTolerance is required" },
            financialSituationStable = requireNotNull(body.financialSituationStable) {
                "financialSituationStable is required"
            },
            esgPreference = body.esgPreference,
        )
        val (application, recommendation) = onboarding.submitQuestionnaire(id, partyOf(party), answers)
        return QuestionnaireResponse(ApplicationResponse.from(application), RecommendationResponse.from(recommendation))
    }

    @GET
    @Path("/{id}/recommendation")
    @Operation(summary = "The strategy recommendation from the risk profile and years to retirement")
    @Authorize(action = "pension.onboarding.inspect", resource = "#id")
    suspend fun recommendation(
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
    ): RecommendationResponse = RecommendationResponse.from(onboarding.recommendation(id, partyOf(party)))

    @POST
    @Path("/{id}/strategy")
    @Operation(summary = "Choose the strategy (default: the recommendation) and issue its key-information document")
    @Authorize(action = "pension.onboarding.strategy", resource = "#id")
    suspend fun chooseStrategy(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
        request: ChooseStrategyRequest?,
    ): ApplicationResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = request ?: ChooseStrategyRequest()
        return ApplicationResponse.from(
            onboarding.chooseStrategy(
                id,
                partyOf(party),
                ChooseStrategyCommand(body.strategyCode, body.acknowledgeWarning ?: false, body.language),
            ),
        )
    }

    @POST
    @Path("/{id}/kid/accept")
    @Operation(summary = "Accept the key-information document that was issued (named by its id)")
    @Authorize(action = "pension.onboarding.kid", resource = "#id")
    suspend fun acceptKid(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
        request: AcceptKidRequest?,
    ): ApplicationResponse {
        requireIdempotencyKey(idempotencyKey)
        val documentId = requireNotNull(request?.documentId) { "documentId is required" }
        return ApplicationResponse.from(onboarding.acceptKid(id, partyOf(party), documentId))
    }

    @POST
    @Path("/{id}/sign")
    @Operation(summary = "SCA-sign the contract (and transfer request); starts the activation workflow")
    @Authorize(action = "pension.onboarding.sign", resource = "#id")
    suspend fun sign(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
        request: SignRequest?,
    ): ApplicationResponse {
        requireIdempotencyKey(idempotencyKey)
        val challenge =
            requireNotNull(request?.scaChallengeId?.takeIf { it.isNotBlank() }) { "scaChallengeId is required" }
        return ApplicationResponse.from(onboarding.sign(id, partyOf(party), challenge))
    }

    @POST
    @Path("/{id}/withdraw")
    @Operation(summary = "Withdraw a signed application within the cooling-off period; the contract closes")
    @Authorize(action = "pension.onboarding.withdraw", resource = "#id")
    suspend fun withdraw(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
    ): ApplicationResponse {
        requireIdempotencyKey(idempotencyKey)
        return ApplicationResponse.from(onboarding.withdraw(id, partyOf(party)))
    }

    @POST
    @Path("/{id}/abandon")
    @Operation(summary = "Abandon an unsigned application")
    @Authorize(action = "pension.onboarding.withdraw", resource = "#id")
    suspend fun abandon(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
    ): ApplicationResponse {
        requireIdempotencyKey(idempotencyKey)
        return ApplicationResponse.from(onboarding.abandon(id, partyOf(party)))
    }
}
