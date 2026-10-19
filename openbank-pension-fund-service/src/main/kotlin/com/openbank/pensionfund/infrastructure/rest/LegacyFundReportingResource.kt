// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.FundReportingService
import com.openbank.pensionfund.domain.model.FundPeriodReport
import com.openbank.pensionfund.domain.model.InstrumentClass
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Period-end fund figures for statutory reporting (#12425, ADR-0336 D4). Aggregate only.
 *
 * `pension-fund.reporting.inspect` is served to staff and to tax-reporting-service's OWN client
 * (pension_fund_rest_ext.rego); it is excluded from `operator-read-any` in rules.yaml, so the
 * shared service-account does not reach it.
 */
@Tag(name = "Reporting", description = "Period-end fund aggregates for regulatory returns (#12425)")
@Path("/api/v1/reporting/funds")
@Produces(MediaType.APPLICATION_JSON)
class LegacyFundReportingResource {

    @Inject
    lateinit var reporting: FundReportingService

    @GET
    @Path("/{fundId}/period-figures")
    @Operation(
        summary = "A fund's balance sheet, YTD P&L, unit roll-forward, portfolio and flows for one period",
        deprecated = true,
    )
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE, Roles.AUDITOR)
    @Authorize(action = "pension-fund.reporting.inspect", resource = "#fundId")
    suspend fun periodFigures(
        @PathParam("fundId") fundId: UUID,
        @QueryParam("periodStart") periodStart: String?,
        @QueryParam("periodEnd") periodEnd: String?,
    ): FundPeriodFiguresResponse {
        val start = date("periodStart", periodStart)
        val end = date("periodEnd", periodEnd)
        return FundPeriodFiguresResponse.from(reporting.periodReport(fundId, start, end))
    }

    private fun date(name: String, value: String?): LocalDate {
        requireNotNull(value) { "query parameter '$name' is required" }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter '$name' must be an ISO date (YYYY-MM-DD)", e)
        }
    }
}
