// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.rest

import com.openbank.libs.security.Roles
import com.openbank.onboarding.application.port.out.BusinessOnboardingRepository
import com.openbank.onboarding.domain.model.BusinessFunnelStage
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * The business half of the onboarding cockpit (ADR-0284 D6).
 *
 * A SEPARATE resource from [OnboardingResource], not three more methods on it: the personal
 * read model is keyed per party and this one per case, so a shared endpoint would have to
 * return a union whose every count is ambiguous about what it counted.
 *
 * Nothing is declared between this KDoc and the class — a top-level declaration here would
 * steal the `@Path` annotation and the resource would silently never be registered (#3371).
 */
@Path("/api/v1/onboarding/business")
@Produces(MediaType.APPLICATION_JSON)
class BusinessOnboardingResource {

    @Inject lateinit var repository: BusinessOnboardingRepository

    /**
     * List business onboarding cases, newest activity first, optionally filtered by funnel stage.
     *
     * Carries the legal name of a company and the party id of the human who started the case, so
     * it is read-restricted exactly like the personal records endpoint.
     *
     * GET /api/v1/onboarding/business/cases?page=0&size=20&stage=NEEDS_REVIEW
     */
    @GET
    @Path("/cases")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, Roles.KYC, Roles.COMPLIANCE)
    suspend fun listCases(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
        @QueryParam("stage") stageParam: String?,
    ): Response {
        // An unparseable filter must never WIDEN the result set (#8699): silently dropping the
        // predicate answers 200 with every case to a caller who asked for one stage and mistyped
        // it. Blank stays "no filter", which is what `?stage=` has always meant.
        // IllegalArgumentException is mapped to 400 by libs-runtime — never a local mapper (#526).
        val stage = stageParam?.takeIf { it.isNotBlank() }?.let { raw ->
            BusinessFunnelStage.entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw IllegalArgumentException(
                    "unknown stage '$raw'; expected one of " +
                        BusinessFunnelStage.entries.joinToString { entry -> entry.name },
                )
        }
        val bounded = size.coerceIn(1, MAX_PAGE_SIZE)
        val items = if (stage == null) {
            repository.listAll(page, bounded)
        } else {
            repository.listByStage(stage, page, bounded)
        }
        return Response.ok(items).build()
    }

    /**
     * One case by its kyb-service case id.
     *
     * GET /api/v1/onboarding/business/cases/{caseId}
     */
    @GET
    @Path("/cases/{caseId}")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, Roles.KYC, Roles.COMPLIANCE)
    suspend fun getCase(@PathParam("caseId") caseId: UUID): Response = repository.findByCaseId(caseId)
        ?.let { Response.ok(it).build() }
        ?: Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("code" to "NOT_FOUND", "message" to "no business onboarding case $caseId"))
            .build()

    /**
     * KPI tiles — case count per funnel stage. Aggregate counts only, no PII.
     *
     * Every stage is present, including the ones at zero. A stage that is simply missing from
     * the map reads as "nothing to see" on a dashboard, which is exactly what a broken
     * projection also looks like.
     *
     * GET /api/v1/onboarding/business/funnel
     */
    @GET
    @Path("/funnel")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, Roles.KYC, Roles.COMPLIANCE)
    suspend fun funnel(): Response {
        val counts = BusinessFunnelStage.entries.associate { it.name to repository.countByStage(it) }
        return Response.ok(mapOf("total" to repository.countAll(), "stages" to counts)).build()
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
