// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.domain.model.FinrepTemplate
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

/**
 * FINREP template REST resource (ADR-0097 Phase 1).
 * Derives F01.01 (Balance Sheet) and F02.00 (P&L) from the ledger trial balance.
 */
@ApplicationScoped
@Path("/api/v1/finrep")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("SERVICE", "ADMIN", "OPERATOR")
class FinrepResource(private val finrepUseCase: FinrepUseCase, private val clock: Clock) {

    @GET
    @Path("/templates/{templateId}")
    suspend fun getTemplate(
        @PathParam("templateId") templateId: String,
        @QueryParam("asOf") @DefaultValue("") asOf: String,
    ): Response {
        val date = if (asOf.isBlank()) LocalDate.now(clock) else LocalDate.parse(asOf)
        val template: FinrepTemplate = finrepUseCase.getTemplate(
            GetFinrepTemplateQuery(templateId = templateId, asOf = date),
        )
        return Response.ok(template).build()
    }
}
