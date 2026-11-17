// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.infrastructure.persistence.repository.BranchBookReconciliations
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.LocalDate
import java.util.UUID

@Path("/api/v1/balances/reconciliation/branch-books")
@Produces(MediaType.APPLICATION_JSON)
class BranchBookReconciliationResource(private val reports: BranchBookReconciliations) {
    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "balance.reconciliation.run")
    suspend fun run(@QueryParam("bankId") bankId: UUID?, @QueryParam("asOf") asOf: String?): Response {
        val date = try {
            LocalDate.parse(requireNotNull(asOf) { "asOf is required" })
        } catch (
            e: java.time.format.DateTimeParseException,
        ) {
            throw IllegalArgumentException("asOf must be an ISO date", e)
        }
        return Response.status(Response.Status.CREATED)
            .entity(reports.run(requireNotNull(bankId) { "bankId is required" }, date)).build()
    }

    @GET
    @Path("/latest")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "balance.reconciliation.read")
    suspend fun latest(@QueryParam("bankId") bankId: UUID?): Response =
        reports.latest(requireNotNull(bankId) { "bankId is required" })?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.NOT_FOUND).build()
}
