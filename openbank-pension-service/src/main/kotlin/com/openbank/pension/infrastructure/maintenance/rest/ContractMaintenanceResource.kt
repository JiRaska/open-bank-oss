// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.maintenance.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.maintenance.ChangeBeneficiariesCommand
import com.openbank.pension.application.maintenance.ChangeNotAuthorisedException
import com.openbank.pension.application.maintenance.ChangeScheduleCommand
import com.openbank.pension.application.maintenance.ContractMaintenanceService
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
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
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.util.UUID

/**
 * Contribution-schedule and beneficiary changes on an existing contract (ADR-0334, #12376).
 *
 * Every route resolves its caller through [ContractAccessGuard] (party header trusted only from
 * the edge relay; another party's contract is 404). Changes act for a vouched participant only,
 * are SCA-bound (the challenge must be signed over the preview's `documentSha256`) and require an
 * `Idempotency-Key` — every POST here is replayed from the store by `IdempotencyReplayFilter`.
 * Staff may read without the header.
 *
 * `@Path` directly above `class` (#3371); nullable params checked in the body (#3104).
 */
@Tag(name = "Pension contract changes", description = "Contribution schedule and beneficiary changes, with history")
@Path("/api/v1/pension/contracts/{contractId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class ContractMaintenanceResource {

    @Inject
    lateinit var maintenance: ContractMaintenanceService

    @Inject
    lateinit var access: ContractAccessGuard

    private fun participant(party: String?): Caller = access.actingParticipant(party)

    @GET
    @Path("/contribution-schedule")
    @Operation(summary = "Original, in-force and pending contribution schedule with the full change history")
    @Authorize(action = "pension.contract.read", resource = "#contractId")
    suspend fun schedule(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): ScheduleViewResponse = ScheduleViewResponse.from(maintenance.schedule(access.readerFor(party), contractId))

    @POST
    @Path("/contribution-schedule/preview")
    @Operation(summary = "Validate a schedule change against the pinned pack; returns effective date and hash to sign")
    @Authorize(action = "pension.contract.schedule", resource = "#contractId")
    suspend fun previewSchedule(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: ScheduleChangeDto?,
    ): SchedulePreviewResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return SchedulePreviewResponse.from(
            maintenance.previewSchedule(participant(party), contractId, body.toDomain()),
        )
    }

    @POST
    @Path("/contribution-schedule/changes")
    @Operation(summary = "Agree a schedule change under SCA; effective from the next collection cycle")
    @Authorize(action = "pension.contract.schedule", resource = "#contractId")
    suspend fun changeSchedule(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: ScheduleChangeDto?,
    ): Response {
        val key = requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val version = maintenance.changeSchedule(
            ChangeScheduleCommand(
                participant(party),
                contractId,
                body.toDomain(),
                requireChallenge(body.scaChallengeId),
                key,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(ScheduleVersionResponse.from(version)).build()
    }

    @GET
    @Path("/beneficiaries")
    @Operation(summary = "Current beneficiary designation with the full designation history")
    @Authorize(action = "pension.contract.read", resource = "#contractId")
    suspend fun beneficiaries(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): BeneficiaryViewResponse {
        val (contract, history) = maintenance.beneficiaries(access.readerFor(party), contractId)
        return BeneficiaryViewResponse.from(contract, history)
    }

    @POST
    @Path("/beneficiaries/preview")
    @Operation(summary = "Validate a complete ordered designation (shares total exactly 100); returns the hash to sign")
    @Authorize(action = "pension.contract.beneficiaries", resource = "#contractId")
    suspend fun previewBeneficiaries(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: BeneficiaryChangeDto?,
    ): BeneficiaryPreviewResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return BeneficiaryPreviewResponse.from(
            maintenance.previewBeneficiaries(participant(party), contractId, body.designations()),
        )
    }

    @POST
    @Path("/beneficiaries/changes")
    @Operation(summary = "Replace the designation under SCA (add, remove, reorder); refused once a death claim exists")
    @Authorize(action = "pension.contract.beneficiaries", resource = "#contractId")
    suspend fun changeBeneficiaries(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: BeneficiaryChangeDto?,
    ): Response {
        val key = requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val version = maintenance.changeBeneficiaries(
            ChangeBeneficiariesCommand(
                participant(party),
                contractId,
                body.designations(),
                requireChallenge(body.scaChallengeId),
                key,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(BeneficiaryVersionResponse.from(version)).build()
    }

    @ServerExceptionMapper
    fun forbidden(e: ChangeNotAuthorisedException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()

    companion object {
        const val PARTY_HEADER = ContractAccessGuard.PARTY_HEADER
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
    }
}
