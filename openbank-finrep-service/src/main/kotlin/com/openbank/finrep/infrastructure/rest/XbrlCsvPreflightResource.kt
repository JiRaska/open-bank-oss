// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.inbound.XbrlCsvPreflightUseCase
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.time.LocalDate

/** Exposes rendering eligibility only; this endpoint never returns an XBRL-CSV artifact. */
@ApplicationScoped
@Path("/api/v1/finrep/templates/{templateId}/xbrl-csv/preflight")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, Roles.OPERATOR)
class XbrlCsvPreflightResource(private val useCase: XbrlCsvPreflightUseCase, private val clock: Clock) {

    @GET
    suspend fun getPreflight(
        @PathParam("templateId") templateId: String,
        @QueryParam("asOf") @DefaultValue("") asOf: String,
    ): Response {
        val date = if (asOf.isBlank()) LocalDate.now(clock) else LocalDate.parse(asOf)
        return Response.ok(useCase.getPreflight(GetXbrlCsvPreflightQuery(templateId, date))).build()
    }
}
