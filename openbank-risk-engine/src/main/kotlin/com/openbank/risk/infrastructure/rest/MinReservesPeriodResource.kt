// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.risk.application.port.`in`.MinReservesPeriodUseCase
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * ČNB minimum reserves over a maintenance period (ADR-0315 D8). Read-only, same readers as the
 * snapshot endpoints. `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Risk", description = "ČNB minimum reserves averaged over the maintenance period (ADR-0315 D8)")
@Path("/api/v1/risk/min-reserves/periods")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, "ROLE_RISK", "ROLE_FINANCE")
class MinReservesPeriodResource {

    @Inject
    lateinit var periods: MinReservesPeriodUseCase

    @GET
    @Operation(summary = "The configured maintenance-period calendar; with asOf, only the period containing it")
    @Authorize(action = "risk.snapshot.read", resource = "")
    fun list(@QueryParam("asOf") asOf: String?): Response {
        val calendar = periods.calendar()
        val selected = asOf?.let { raw -> listOfNotNull(calendar.resolve(parseDate(raw))) } ?: calendar.periods
        return Response.ok(calendar.toResponse(selected)).build()
    }

    @GET
    @Path("/{periodId}")
    @Operation(
        summary = "Requirement, running average of holdings, coverage and daily holding proposal for one period",
    )
    @Authorize(action = "risk.snapshot.read", resource = "#periodId")
    suspend fun get(@PathParam("periodId") periodId: String, @QueryParam("asOf") asOf: String?): Response =
        Response.ok(periods.analysePeriod(periodId, asOf?.let { parseDate(it) }).toResponse()).build()

    private fun parseDate(raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("query parameter 'asOf' must be an ISO date (YYYY-MM-DD)", e)
    }
}
