// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.domain.model.FinrepTemplate
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
 * FINREP template REST resource (ADR-0097 Phase 1).
 * Derives F01.01 (Balance Sheet) and F02.00 (P&L) from the ledger trial balance.
 *
 * Roles come from [Roles], never string literals: this class shipped with
 * `@RolesAllowed("SERVICE", "ADMIN", "OPERATOR")` and the Keycloak realm issues no role by any of
 * those three names (it issues `ROLE_ADMIN`, `ROLE_OPERATOR`, …), so every authenticated request
 * to this resource was rejected 403 for every caller. `ROLE_SERVICE` is deliberately NOT in the
 * replacement set: it exists as a [Roles] constant but is granted to no realm client, so listing
 * it would re-add a dead entry that reads like an M2M grant. The only caller is the admin-UI BFF
 * (see the finrep-service ingress allow-list), whose operators carry ROLE_ADMIN / ROLE_OPERATOR.
 */
@ApplicationScoped
@Path("/api/v1/finrep")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, Roles.OPERATOR)
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
