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
        // A filter that cannot be parsed must never widen the result set. `getOrNull()` used to
        // turn an unrecognised stage into a legal null, and `listRecords` omits the predicate
        // entirely when the stage is null — so `?stage=KYC_OPEEN` answered 200 with every record,
        // PII included, for a caller who asked for one stage and mistyped it (#8699).
        //
        // Blank is treated as ABSENT, not as unparseable: `?stage=` has always meant "no filter",
        // and #8699 asks specifically that absent stay distinguishable from unparseable.
        // `IllegalArgumentException` is mapped to 400 by libs-runtime's shared
        // `IllegalArgumentExceptionMapper` — no service-local mapper (#526).
        val stage = stageParam?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { FunnelStage.valueOf(raw.uppercase()) }.getOrElse {
                throw IllegalArgumentException(
                    "unknown stage '$raw'; expected one of " +
                        FunnelStage.entries.joinToString { entry -> entry.name },
                )
            }
        }
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
