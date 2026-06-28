// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.rest

import com.openbank.libs.security.Roles
import com.openbank.onboarding.application.port.`in`.OnboardingUseCase
import com.openbank.onboarding.application.usecase.OnboardingRecordNotFoundException
import com.openbank.onboarding.domain.model.FunnelStage
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class OnboardingResource {

    @Inject lateinit var useCase: OnboardingUseCase

    /**
     * List onboarding records, optionally filtered by funnel stage.
     * Contains PII (legalName, email) — requires ROLE_VIEWER or higher.
     *
     * GET /api/v1/onboarding/records?page=0&size=20&stage=KYC_OPEN
     */
    @GET
    @Path("/records")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, Roles.KYC, Roles.COMPLIANCE)
    suspend fun listRecords(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
        @QueryParam("stage") stageParam: String?,
    ): Response {
        val stage = stageParam?.uppercase()?.let { runCatching { FunnelStage.valueOf(it) }.getOrNull() }
        return Response.ok(useCase.listRecords(page, size.coerceIn(1, 100), stage)).build()
    }

    /**
     * Get onboarding record for a single party.
     * Contains PII (legalName, email) — requires ROLE_VIEWER or higher.
     *
     * GET /api/v1/onboarding/records/{partyId}
     */
    @GET
    @Path("/records/{partyId}")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, Roles.KYC, Roles.COMPLIANCE)
    suspend fun getRecord(@PathParam("partyId") partyId: UUID): Response = try {
        Response.ok(useCase.getRecord(partyId)).build()
    } catch (e: OnboardingRecordNotFoundException) {
        Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("code" to "NOT_FOUND", "message" to e.message))
            .build()
    }

    /**
     * Funnel KPI tiles — count per stage.
     * Aggregate counts only (no PII) — requires ROLE_VIEWER or higher.
     *
     * GET /api/v1/onboarding/funnel
     */
    @GET
    @Path("/funnel")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, Roles.KYC, Roles.COMPLIANCE)
    suspend fun funnelCounts(): Response = Response.ok(useCase.funnelCounts()).build()
}
