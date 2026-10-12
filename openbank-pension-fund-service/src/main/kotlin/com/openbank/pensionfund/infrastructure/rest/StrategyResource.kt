// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.StrategyChange
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Strategies", description = "Fund strategies, glide paths and governed changes (ADR-0334)")
@Path("/api/v1/strategies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StrategyResource {

    @Inject
    lateinit var admin: FundAdministrationService

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Operation(summary = "Create a strategy (static allocation, optionally a lifecycle glide path)")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.strategy.manage")
    suspend fun create(request: StrategyRequest?): Response {
        val strategy = admin.createStrategy(requireNotNull(request) { "request body is required" }.toDefinition())
        return Response.status(Response.Status.CREATED).entity(strategy).build()
    }

    @GET
    @Operation(summary = "List strategies")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.strategy.read")
    suspend fun list(): List<FundStrategy> = admin.strategies()

    @GET
    @Path("/{strategyId}")
    @Operation(summary = "Read one strategy")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.strategy.read", resource = "#strategyId")
    suspend fun get(@PathParam("strategyId") strategyId: UUID): FundStrategy = admin.strategy(strategyId)

    /** Nullable on purpose: JAX-RS injects null for an absent query parameter (#3104). */
    @GET
    @Path("/{strategyId}/allocation")
    @Operation(summary = "Target allocation for a participant with the given years to retirement")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.strategy.read", resource = "#strategyId")
    suspend fun allocation(
        @PathParam("strategyId") strategyId: UUID,
        @QueryParam("yearsToRetirement") yearsToRetirement: Int?,
    ): List<AllocationTarget> {
        val years = requireNotNull(yearsToRetirement) { "query parameter 'yearsToRetirement' is required" }
        return admin.allocationFor(strategyId, years)
    }

    @POST
    @Path("/{strategyId}/changes")
    @Operation(summary = "Submit an allocation change (maker); needs approval and a notice period")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.strategy.change.submit", resource = "#strategyId")
    suspend fun submitChange(@PathParam("strategyId") strategyId: UUID, request: StrategyChangeDto?): Response {
        val change = admin.submitChange(
            strategyId,
            requireNotNull(request) { "request body is required" }.toRequest(),
            identity.actor(),
        )
        return Response.status(Response.Status.CREATED).entity(change).build()
    }

    @GET
    @Path("/{strategyId}/changes")
    @Operation(summary = "Changes submitted against a strategy, newest first")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.strategy.read", resource = "#strategyId")
    suspend fun changes(@PathParam("strategyId") strategyId: UUID): List<StrategyChange> =
        admin.strategyChanges(strategyId)
}
