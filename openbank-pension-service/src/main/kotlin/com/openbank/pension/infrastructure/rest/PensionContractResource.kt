// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.EarlyTerminationCommand
import com.openbank.pension.application.port.`in`.IncentiveEvaluationCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.pack.SurrenderInputs
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.dto.ContractResponse
import com.openbank.pension.infrastructure.rest.dto.CreateContractRequest
import com.openbank.pension.infrastructure.rest.dto.EarlyTerminationRequest
import com.openbank.pension.infrastructure.rest.dto.EarlyTerminationResponse
import com.openbank.pension.infrastructure.rest.dto.ElectStrategyRequest
import com.openbank.pension.infrastructure.rest.dto.IncentiveEvaluationRequest
import com.openbank.pension.infrastructure.rest.dto.IncentiveResultResponse
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
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
 * Participant-side pension contracts (ADR-0334 slice S1).
 *
 * `@Path` sits directly above `class`: a Kotlin annotation binds to the NEXT declaration, so a
 * helper slipped in between would steal it and every route would 404 on a running pod (#3371).
 * Parameters JAX-RS may leave absent are declared nullable and checked in the body (#3104).
 *
 * Ownership: every route takes `X-Customer-Party-Id`, stamped by the customer edge from the token
 * it validated. A caller without a staff role MUST send it, and is then confined to that party's
 * contracts — someone else's contract answers 404, the same as an unknown id. Staff (operator,
 * admin, compliance) may omit it to read; writes always act for a participant (the use case
 * refuses a staff write, and OPA grants staff read only).
 */
@Tag(name = "Pension", description = "Pension contracts, strategy elections and jurisdiction-pack evaluation")
@Path("/api/v1/pension/contracts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class PensionContractResource {

    @Inject
    lateinit var contracts: PensionContractUseCase

    @Inject
    lateinit var access: ContractAccessGuard

    @POST
    @Operation(summary = "Create a DRAFT contract under the jurisdiction pack in force today")
    @Authorize(action = "pension.contract.create")
    suspend fun create(
        @HeaderParam("X-Customer-Party-Id") participantPartyId: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: CreateContractRequest?,
    ): Response {
        val participant = requireNotNull(access.actingParticipant(participantPartyId).customerPartyId)
        val body = requireNotNull(request) { "request body is required" }
        val schedule = requireNotNull(body.schedule) { "schedule is required" }
        val contract = contracts.createDraft(
            CreateDraftCommand(
                participantPartyId = participant,
                productLine = requireNotNull(body.productLine) { "productLine is required" },
                jurisdiction = requireNotNull(body.jurisdiction) { "jurisdiction is required" },
                providerEntityId = requireNotNull(body.providerEntityId) { "providerEntityId is required" },
                providerType = requireNotNull(body.providerType) { "providerType is required" },
                birthDate = requireNotNull(body.birthDate) { "birthDate is required" },
                residencyCountry = body.residencyCountry,
                residencyEvidence = body.residencyEvidence.orEmpty().mapIndexed { i, e ->
                    requireNotNull(e) { "residencyEvidence[$i] must not be null" }
                }.toSet(),
                hasGuardian = body.hasGuardian ?: false,
                schedule = ContributionSchedule(
                    amount = requireNotNull(schedule.amount) { "schedule.amount is required" },
                    currency = requireNotNull(schedule.currency) { "schedule.currency is required" },
                    frequency = requireNotNull(schedule.frequency) { "schedule.frequency is required" },
                    employerAmount = schedule.employerAmount ?: BigDecimal.ZERO,
                ),
                initialStrategy = requireNotNull(body.strategyCode) { "strategyCode is required" },
                beneficiaries = body.beneficiaries.orEmpty().mapIndexed { i, b ->
                    requireNotNull(b) { "beneficiaries[$i] must not be null" }.toDomain(i)
                },
                idempotencyKey = idempotencyKey(idempotencyKey),
            ),
        )
        return Response.status(Response.Status.CREATED).entity(ContractResponse.from(contract)).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Read one contract with its strategy history")
    @Authorize(action = "pension.contract.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") party: String?): ContractResponse =
        ContractResponse.from(contracts.get(access.readerFor(party), id))

    @POST
    @Path("/{id}/submit")
    @Operation(summary = "DRAFT -> PENDING_ACTIVATION")
    @Authorize(action = "pension.contract.submit", resource = "#id")
    suspend fun submit(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): ContractResponse = ContractResponse.from(
        contracts.submit(
            access.actingParticipant(party).also {
                idempotencyKey(idempotencyKey)
            },
            id,
        ),
    )

    @POST
    @Path("/{id}/activate")
    @Operation(summary = "PENDING_ACTIVATION -> ACTIVE; the contract start date is set today")
    @Authorize(action = "pension.contract.activate", resource = "#id")
    suspend fun activate(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): ContractResponse = ContractResponse.from(
        contracts.activate(
            access.actingParticipant(party).also {
                idempotencyKey(idempotencyKey)
            },
            id,
        ),
    )

    @PUT
    @Path("/{id}/strategy")
    @Operation(summary = "Elect or change the investment strategy; earlier elections stay as history")
    @Authorize(action = "pension.contract.strategy", resource = "#id")
    suspend fun electStrategy(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        request: ElectStrategyRequest?,
    ): ContractResponse {
        val body = requireNotNull(request) { "request body is required" }
        val code = requireNotNull(body.strategyCode) { "strategyCode is required" }
        return ContractResponse.from(
            contracts.electStrategy(access.actingParticipant(party), id, code, body.effectiveFrom),
        )
    }

    @POST
    @Path("/{id}/suspend")
    @Operation(summary = "Pause contributions: ACTIVE -> SUSPENDED")
    @Authorize(action = "pension.contract.suspend", resource = "#id")
    suspend fun suspend(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): ContractResponse = ContractResponse.from(
        contracts.suspendContributions(
            access.actingParticipant(party).also {
                idempotencyKey(idempotencyKey)
            },
            id,
        ),
    )

    @POST
    @Path("/{id}/resume")
    @Operation(summary = "Resume contributions: SUSPENDED -> ACTIVE")
    @Authorize(action = "pension.contract.resume", resource = "#id")
    suspend fun resume(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): ContractResponse = ContractResponse.from(
        contracts.resumeContributions(
            access.actingParticipant(party).also {
                idempotencyKey(idempotencyKey)
            },
            id,
        ),
    )

    @POST
    @Path("/{id}/incentive-evaluation")
    @Operation(summary = "Evaluate the pinned pack's incentives for one contribution amount")
    @Authorize(action = "pension.contract.read", resource = "#id")
    suspend fun evaluateIncentives(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: IncentiveEvaluationRequest?,
    ): List<IncentiveResultResponse> {
        idempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return contracts.evaluateIncentives(
            IncentiveEvaluationCommand(
                caller = access.readerFor(party),
                contractId = id,
                contribution = requireNotNull(body.contribution) { "contribution is required" },
                period = requireNotNull(body.period) { "period is required" },
                employerContributionAnnual = body.employerContributionAnnual ?: BigDecimal.ZERO,
                sharedCapUsed = body.sharedCapUsed.orEmpty().mapValues { (group, used) ->
                    requireNotNull(used) { "sharedCapUsed['$group'] must not be null" }
                },
            ),
        ).map(IncentiveResultResponse::from)
    }

    @POST
    @Path("/{id}/early-termination")
    @Operation(summary = "Surrender preview; with confirm=true the contract moves to TERMINATING")
    @Authorize(action = "pension.contract.terminate", resource = "#id")
    suspend fun earlyTermination(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: EarlyTerminationRequest?,
    ): EarlyTerminationResponse {
        idempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val received = body.incentivesReceived.orEmpty().mapValues { (incentive, years) ->
            years.orEmpty().mapValues { (year, amount) ->
                requireNotNull(amount) { "incentivesReceived['$incentive'][$year] must not be null" }
            }
        }
        val result = contracts.requestEarlyTermination(
            EarlyTerminationCommand(
                caller = access.actingParticipant(party),
                contractId = id,
                inputs = SurrenderInputs(
                    currentValue = requireNotNull(body.currentValue) { "currentValue is required" },
                    incentivesReceived = received,
                ),
                confirm = body.confirm ?: false,
            ),
        )
        return EarlyTerminationResponse.from(result.contract, result.preview)
    }
}

private const val MAX_IDEMPOTENCY_KEY_LENGTH = 256

/** Required on every POST (money-path idempotency rule); validated before any work is done. */
private fun idempotencyKey(value: String?): String {
    val key = requireNotNull(value) { "header 'Idempotency-Key' is required" }
    require(key.isNotBlank() && key.length <= MAX_IDEMPOTENCY_KEY_LENGTH) {
        "Idempotency-Key must be 1..$MAX_IDEMPOTENCY_KEY_LENGTH characters"
    }
    return key
}
