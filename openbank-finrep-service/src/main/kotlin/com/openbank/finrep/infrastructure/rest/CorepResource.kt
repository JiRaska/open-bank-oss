// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.CorepUseCase
import com.openbank.finrep.application.port.inbound.GetCorepTemplateQuery
import com.openbank.finrep.application.port.inbound.TrialBalanceEvidence
import com.openbank.finrep.domain.model.CorepTemplate
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

/**
 * COREP template REST resource (ADR-0097 Phase 2, first increment).
 *
 * Derives C 01.00 (Own Funds) from the ledger trial balance, structured data only — NOT EBA
 * XBRL/DPM taxonomy output, and there is NO ČNB transmission channel. Capital-structure rows are
 * reported as explicit, flagged zeros ([CorepCell.isDataGap]) because the ledger's chart of
 * accounts has no capital-structure GL accounts today; see [com.openbank.finrep.domain.mapper.C0100Mapper]
 * for the detailed rationale.
 *
 * Roles come from [Roles], never string literals — see the note on
 * [com.openbank.finrep.infrastructure.rest.FinrepResource]: the shipped
 * `@RolesAllowed("SERVICE", "ADMIN", "OPERATOR")` named three roles the realm does not issue, so
 * this resource answered 403 to every caller.
 */
@ApplicationScoped
@Path("/api/v1/corep")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, Roles.OPERATOR)
class CorepResource(private val corepUseCase: CorepUseCase, private val clock: Clock) {

    @GET
    @Path("/templates/{templateId}")
    suspend fun getTemplate(
        @PathParam("templateId") templateId: String,
        @QueryParam("asOf") @DefaultValue("") asOf: String,
        @QueryParam("evidence") @DefaultValue("FROZEN") evidence: String = "FROZEN",
    ): Response {
        val date = if (asOf.isBlank()) LocalDate.now(clock) else LocalDate.parse(asOf)
        val template: CorepTemplate = corepUseCase.getTemplate(
            GetCorepTemplateQuery(
                templateId = templateId,
                asOf = date,
                evidence = TrialBalanceEvidence.valueOf(evidence.uppercase()),
            ),
        )
        return Response.ok(template).build()
    }
}
