// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.risk.application.port.`in`.CashFlowUseCase
import com.openbank.risk.application.port.`in`.IrrbbUseCase
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
 * Balance-sheet snapshot runs (ADR-0314 D1–D4).
 *
 * NOTE the annotation order: `@Path` sits immediately above `class`. A Kotlin annotation binds to
 * the NEXT declaration, so a top-level helper slipped in between silently steals it and the
 * resource is never registered (#3371).
 */
@Tag(name = "Risk", description = "Balance-sheet snapshot runs tied out to the ledger (ADR-0314)")
@Path("/api/v1/risk/snapshots")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
// #10618: the risk and finance departments read every endpoint here; only ROLE_RISK (never FINANCE)
// joins the write below. Literal names, like lending's ROLE_CREDIT_RISK: adding them to libs Roles.kt
// would rebuild the whole fleet for two constants.
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, "ROLE_RISK", "ROLE_FINANCE")
class RiskResource {

    @Inject
    lateinit var snapshots: SnapshotUseCase

    @Inject
    lateinit var cashFlows: CashFlowUseCase

    @Inject
    lateinit var irrbb: IrrbbUseCase

    @POST
    @Operation(summary = "Build (or replay) the balance-sheet snapshot for an as-of date")
    @Authorize(action = "risk.snapshot.create")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, "ROLE_RISK")
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

    /** Bounded list for the console (#10618); the mismatches themselves are on the run manifest. */
    @GET
    @Operation(summary = "The most recently recorded snapshot runs, newest first (limit 1..100, default 25)")
    @Authorize(action = "risk.snapshot.read", resource = "")
    suspend fun list(@QueryParam("limit") limit: Int?): Response =
        Response.ok(SnapshotRunListResponse(snapshots.listRuns(boundedLimit(limit)).map { it.toDto() })).build()

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

    @GET
    @Path("/{id}/instruments")
    @Operation(summary = "Contract-level instruments of a TIED_OUT run (ADR-0314 D4); 409 for an UNTIED one")
    @Authorize(action = "risk.snapshot.read", resource = "#id")
    suspend fun instruments(@PathParam("id") id: UUID): Response {
        val instruments = snapshots.getInstruments(id)
        val run = snapshots.getRun(id)
        return Response.ok(InstrumentsResponse(run.id, run.asOf.toString(), instruments.map { it.toDto() })).build()
    }

    /**
     * Behavioural cash flows of a TIED_OUT run under a curve set, derived on request and never
     * stored (ADR-0314 D6). Same gate as positions: an UNTIED run answers 409.
     */
    @GET
    @Path("/{id}/cash-flows")
    @Operation(summary = "Bucketed cash flows and PV of a TIED_OUT run under a curve set; 409 for an UNTIED one")
    @Authorize(action = "risk.snapshot.read", resource = "#id")
    suspend fun cashFlows(@PathParam("id") id: UUID, @QueryParam("curveSetId") curveSetId: String?): Response =
        Response.ok(cashFlows.project(id, parseCurveSetId(curveSetId)).toResponse()).build()

    /**
     * IRRBB of a TIED_OUT run (ADR-0313 phase 1): repricing gap, ΔEVE under the six BCBS d368
     * scenarios, ΔNII (parallel up/down). `tier1Capital` is optional and only ever the caller's:
     * without it the outlier ratio is not computed. Same gates as cash flows: UNTIED → 409.
     */
    @GET
    @Path("/{id}/irrbb")
    @Operation(summary = "IRRBB (repricing gap, ΔEVE, ΔNII) of a TIED_OUT run under a curve set; 409 for an UNTIED one")
    @Authorize(action = "risk.snapshot.read", resource = "#id")
    suspend fun irrbb(
        @PathParam("id") id: UUID,
        @QueryParam("curveSetId") curveSetId: String?,
        @QueryParam("tier1Capital") tier1Capital: String?,
    ): Response {
        val tier1 = tier1Capital?.takeIf { it.isNotBlank() }?.let {
            requireNotNull(it.trim().toBigDecimalOrNull()) { "query parameter 'tier1Capital' must be a decimal number" }
        }
        return Response.ok(irrbb.analyse(id, parseCurveSetId(curveSetId), tier1).toResponse()).build()
    }

    private fun parseCurveSetId(curveSetId: String?): UUID {
        val raw = requireNotNull(curveSetId) { "query parameter 'curveSetId' is required" }
        return try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("query parameter 'curveSetId' must be a UUID", e)
        }
    }
}
