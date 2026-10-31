// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.StrategyChange
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Strategies", description = "Fund strategies, glide paths and governed changes (ADR-0334)")
@Path("/api/v1/strategy-changes")
@Produces(MediaType.APPLICATION_JSON)
class StrategyChangeResource {

    @Inject
    lateinit var admin: FundAdministrationService

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @Path("/{changeId}")
    @Operation(summary = "Read one strategy change")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.strategy.read", resource = "#changeId")
    suspend fun get(@PathParam("changeId") changeId: UUID): StrategyChange = admin.strategyChange(changeId)

    @POST
    @Path("/{changeId}/approve")
    @Operation(summary = "Approve a change (checker, never the submitter); participants are notified from today")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.strategy.change.approve", resource = "#changeId")
    suspend fun approve(@PathParam("changeId") changeId: UUID): StrategyChange =
        admin.approveChange(changeId, identity.actor())

    @POST
    @Path("/{changeId}/reject")
    @Operation(summary = "Reject a change (checker)")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.strategy.change.approve", resource = "#changeId")
    suspend fun reject(@PathParam("changeId") changeId: UUID): StrategyChange =
        admin.rejectChange(changeId, identity.actor())

    @POST
    @Path("/{changeId}/apply")
    @Operation(summary = "Apply an approved change on or after its effective date")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.strategy.change.apply", resource = "#changeId")
    suspend fun apply(@PathParam("changeId") changeId: UUID): FundStrategy = admin.applyChange(changeId)
}
