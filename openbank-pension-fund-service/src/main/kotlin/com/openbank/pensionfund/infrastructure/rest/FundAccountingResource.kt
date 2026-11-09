// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.infrastructure.accounting.FundAccountingDayEvidence
import com.openbank.pensionfund.infrastructure.accounting.FundAccountingEvidence
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.time.LocalDate
import java.util.UUID

@Path("/api/v2/funds/{fundId}/accounting-days/{date}")
@Produces(MediaType.APPLICATION_JSON)
class FundAccountingResource(
    private val evidence: FundAccountingEvidence,
    private val funds: FundAdministrationService,
) {
    @GET
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.fund.read", resource = "#fundId")
    suspend fun get(
        @PathParam("fundId") fundId: UUID,
        @PathParam("date") date: LocalDate,
        @QueryParam("bookKind") kind: String?,
    ): FundAccountingDayEvidence {
        funds.fund(fundId)
        return evidence.day(fundId, date, kind ?: "PENSION_FUND")
    }
}
