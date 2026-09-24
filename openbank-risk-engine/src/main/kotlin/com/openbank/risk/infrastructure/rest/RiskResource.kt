// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.risk.application.port.`in`.CashFlowUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Balance-sheet snapshot runs (ADR-0314 D1–D3).
 *
 * NOTE the annotation order: `@Path` sits immediately above `class`. A Kotlin annotation binds to
 * the NEXT declaration, so a top-level helper slipped in between silently steals it and the
 * resource is never registered (#3371).
 */
@Tag(name = "Risk", description = "Balance-sheet snapshot runs tied out to the ledger (ADR-0314)")
@Path("/api/v1/risk/snapshots")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class RiskResource {

    @Inject
    lateinit var snapshots: SnapshotUseCase

    @Inject
    lateinit var cashFlows: CashFlowUseCase

    @POST
    @Operation(summary = "Build (or replay) the balance-sheet snapshot for an as-of date")
    @Authorize(action = "risk.snapshot.create")
    suspend fun create(request: CreateSnapshotRequest?): Response {
        val raw = requireNotNull(request?.asOf) { "field 'asOf' is required" }
        val asOf = try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("field 'asOf' must be an ISO date (YYYY-MM-DD)", e)
        }
        val outcome = snapshots.createSnapshot(asOf)
        val status = if (outcome.replayed) Response.Status.OK else Response.Status.CREATED
        return Response.status(status).entity(outcome.run.toResponse()).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Run manifest, including every tie-out mismatch")
    @Authorize(action = "risk.snapshot.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): Response = Response.ok(snapshots.getRun(id).toResponse()).build()

    @GET
    @Path("/{id}/positions")
    @Operation(summary = "Positions of a TIED_OUT run; 409 with the mismatches for an UNTIED one")
    @Authorize(action = "risk.snapshot.read", resource = "#id")
    suspend fun positions(@PathParam("id") id: UUID): Response {
        val positions = snapshots.getPositions(id)
        val run = snapshots.getRun(id)
        return Response.ok(PositionsResponse(run.id, run.asOf.toString(), positions.map { it.toDto() })).build()
    }

    /**
     * Behavioural cash flows of a TIED_OUT run under a curve set, derived on request and never
     * stored (ADR-0314 D6). Same gate as positions: an UNTIED run answers 409.
     */
    @GET
    @Path("/{id}/cash-flows")
    @Operation(summary = "Bucketed cash flows and PV of a TIED_OUT run under a curve set; 409 for an UNTIED one")
    @Authorize(action = "risk.snapshot.read", resource = "#id")
    suspend fun cashFlows(@PathParam("id") id: UUID, @QueryParam("curveSetId") curveSetId: String?): Response {
        val raw = requireNotNull(curveSetId) { "query parameter 'curveSetId' is required" }
        val setId = try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("query parameter 'curveSetId' must be a UUID", e)
        }
        return Response.ok(cashFlows.project(id, setId).toResponse()).build()
    }
}
