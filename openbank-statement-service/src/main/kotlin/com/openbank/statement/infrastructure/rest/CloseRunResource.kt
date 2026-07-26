// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.statement.application.port.`in`.CloseRunQueryUseCase
import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.domain.model.CloseTrigger
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Operator surface for the scheduled close cadence (ADR-0069 D3 / issue #470): see whether the cron
 * ran, how many pockets closed/failed/skipped, inspect failures, and trigger a manual catch-up retry.
 * Read-only views are visible to viewers/auditors; the manual retry is operator/admin only.
 */
@Path("/api/v1/statements/close-runs")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Statement close runs", description = "Scheduled close cadence telemetry + manual retry (ADR-0069)")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_API")
class CloseRunResource(private val query: CloseRunQueryUseCase, private val runClose: RunCloseUseCase) {

    @GET
    @Authorize(action = "statement.close-run.list", resource = "")
    @Operation(summary = "Recent close runs (newest first)")
    fun recent(@QueryParam("limit") @DefaultValue("20") limit: Int): Uni<Response> =
        query.recentRuns(limit).map { Response.ok(it).build() }

    @GET
    @Path("/latest")
    @Authorize(action = "statement.close-run.read", resource = "")
    @Operation(summary = "The most recent close run, or 204 if the cadence has never run")
    fun latest(): Uni<Response> = query.latestRun().map { run ->
        if (run == null) Response.noContent().build() else Response.ok(run).build()
    }

    @GET
    @Path("/{runId}/failures")
    @Authorize(action = "statement.close-run.read", resource = "#runId")
    @Operation(summary = "Per-pocket failures recorded within a close run")
    fun failures(@PathParam("runId") runId: UUID): Uni<Response> =
        query.failuresForRun(runId).map { Response.ok(it).build() }

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "statement.close-run.trigger", resource = "")
    @Operation(summary = "Trigger a manual catch-up close pass now (operator retry)")
    fun trigger(): Uni<Response> = runClose.runClose(CloseTrigger.MANUAL).map { Response.accepted(it).build() }
}
