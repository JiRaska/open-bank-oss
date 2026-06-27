// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.rest

import com.openbank.analytics.infrastructure.reconcile.ReconciliationJob
import com.openbank.analytics.infrastructure.reconcile.ReconciliationResult
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Operator/audit surface for the analytics reconciliation job (ADR-0022).
 *
 * Reconciliation evidence (warehouse == source-of-record at time T) is audit material, so both the
 * manual trigger and the last-result read are gated to [Roles.AUDITOR]/[Roles.ADMIN]/[Roles.COMPLIANCE]
 * — never `@PermitAll` (cf. the K7 audit-trail finding). The trigger is idempotent in effect (it just
 * re-runs a read-only comparison) but is a POST because it has a side effect (records a result).
 */
@Path("/api/v1/analytics/reconciliation")
@Produces(MediaType.APPLICATION_JSON)
class ReconciliationResource {

    @Inject lateinit var job: ReconciliationJob

    @POST
    @Path("/run")
    @RolesAllowed(Roles.AUDITOR, Roles.ADMIN, Roles.COMPLIANCE)
    suspend fun trigger(): ReconciliationResult = job.run("manual")

    @GET
    @Path("/last")
    @RolesAllowed(Roles.AUDITOR, Roles.ADMIN, Roles.COMPLIANCE)
    fun last(): Response =
        job.lastResult()?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.NO_CONTENT).build()
}
