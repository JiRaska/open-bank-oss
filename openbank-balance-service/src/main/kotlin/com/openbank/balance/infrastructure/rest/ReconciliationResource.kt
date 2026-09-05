// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
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
import java.time.Clock
import java.time.LocalDate

/**
 * ADR-0039 Phase A — read-only reconciliation reporting. Exposes the latest control-account ⇄
 * sub-ledger tie-out and an on-demand trigger (the scheduler runs it daily). No endpoint mutates a
 * balance.
 *
 * Access control (K7 / ADR-0018): never `@PermitAll`. The tie-out report is financial-control
 * evidence — readable by service callers (statement reconciliation, ADR-0035), auditors, viewers
 * and operators; the on-demand re-run is restricted to operators/admins (the daily run is an
 * in-process scheduler that does not traverse this HTTP boundary). Locked by
 * ReconciliationResourceSecurityTest.
 */
@Path("/api/v1/balances/reconciliation")
@Produces(MediaType.APPLICATION_JSON)
class ReconciliationResource(private val reconcile: ReconcileBalancesUseCase, private val clock: Clock) {

    @GET
    @Path("/latest")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "balance.reconciliation.read")
    suspend fun latest(): Response = reconcile.latest()
        ?.let { Response.ok(it).build() }
        ?: Response.status(Response.Status.NOT_FOUND).build()

    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "balance.reconciliation.run")
    suspend fun run(@QueryParam("asOf") asOf: String?): Response {
        // Blank means "omitted" (the fuzzer found `?asOf=` 500ing, #8832); a malformed value is a
        // client error — IllegalArgumentException maps to 400 via libs-runtime CommonExceptionMappers,
        // where a raw DateTimeParseException would surface as a 500.
        val date = asOf?.takeIf { it.isNotBlank() }?.let {
            try {
                LocalDate.parse(it)
            } catch (ex: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException(
                    "query parameter 'asOf' must be an ISO-8601 date (yyyy-MM-dd), got '$it'",
                )
            }
        } ?: LocalDate.now(clock)
        val report = reconcile.reconcile(date)
        return Response.status(Response.Status.CREATED).entity(report).build()
    }
}
