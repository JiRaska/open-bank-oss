// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.infrastructure.rest

import com.openbank.analytics.application.BackfillReport
import com.openbank.analytics.application.BackfillService
import com.openbank.analytics.application.SensitiveReloadService
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.IngestSource
import com.openbank.libs.analytics.MakerCheckerViolation
import com.openbank.libs.analytics.Proposal
import com.openbank.libs.security.Roles
import com.openbank.libs.security.actorName
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import java.time.Clock
import java.time.Instant

/** Body proposing a window reload. `kind` is BACKFILL | CORRECTION | INITIAL_LOAD. ISO-8601 timestamps. */
data class ReloadProposalDto(
    val kind: String,
    val from: String? = null,
    val to: String? = null,
    val aggregateType: String? = null,
    val aggregateId: String? = null,
    val reason: String,
)

/** Body for a checker decision (approve/reject). */
data class DecisionDto(val reason: String? = null)

/**
 * Operator surface for recovery loads into the 10-year log of record (ADR-0022 / ADR-0023 F3).
 *
 * Reloads are **four-eyes**: one operator proposes, a *different* one approves, only then can it
 * execute. The segregation of duties is enforced by the [Proposal] state machine (an approver equal
 * to the proposer is rejected with [MakerCheckerViolation] → HTTP 409), not by convention. All verbs
 * are gated to [Roles.ADMIN]/[Roles.AUDITOR] (never `@PermitAll`, cf. K7) and every transition is
 * recorded as audit evidence (who proposed, who approved, who executed).
 */
@Path("/api/v1/analytics/backfill")
@Produces(MediaType.APPLICATION_JSON)
class BackfillResource {

    @Inject lateinit var reloads: SensitiveReloadService

    @Inject lateinit var clock: Clock

    @Inject lateinit var service: BackfillService

    /** Step 1 — propose a reload. Returns the PROPOSED proposal id to approve. */
    @POST
    @Path("/proposals")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    suspend fun propose(@Context ctx: SecurityContext, dto: ReloadProposalDto): Proposal<BackfillRequest> {
        val kind = runCatching { IngestSource.valueOf(dto.kind.uppercase()) }.getOrNull()
            ?: throw badRequest("unknown reload kind '${dto.kind}'")
        if (kind == IngestSource.STREAM) throw badRequest("STREAM is the live path, not a reload kind")
        val from = if (kind == IngestSource.INITIAL_LOAD) Instant.EPOCH else parseRequired(dto.from, "from")
        val to = dto.to?.let(Instant::parse) ?: Instant.now(clock)
        return reloads.propose(
            BackfillRequest(
                source = kind,
                from = from,
                to = to,
                aggregateType = dto.aggregateType,
                aggregateId = dto.aggregateId,
                reason = dto.reason,
                requestedBy = ctx.actorName,
            ),
        )
    }

    /** Step 2 — a *different* operator approves. Self-approval ⇒ 409. */
    @POST
    @Path("/proposals/{id}/approve")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    suspend fun approve(
        @Context ctx: SecurityContext,
        @PathParam("id") id: String,
        dto: DecisionDto,
    ): Proposal<BackfillRequest> = reloads.approve(id, ctx.actorName, dto.reason)

    /** Optional — reject a pending proposal. */
    @POST
    @Path("/proposals/{id}/reject")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    suspend fun reject(
        @Context ctx: SecurityContext,
        @PathParam("id") id: String,
        dto: DecisionDto,
    ): Proposal<BackfillRequest> = reloads.reject(id, ctx.actorName, dto.reason)

    /** Step 3 — execute an APPROVED proposal. Runs the real reload, then marks it EXECUTED. */
    @POST
    @Path("/proposals/{id}/execute")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    suspend fun execute(@PathParam("id") id: String): BackfillReport = reloads.execute(id)

    @GET
    @Path("/proposals")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    suspend fun list(): List<Proposal<BackfillRequest>> = reloads.list()

    @GET
    @Path("/proposals/{id}")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    suspend fun get(@PathParam("id") id: String): Response = reloads.get(id)?.let { Response.ok(it).build() }
        ?: Response.status(Response.Status.NOT_FOUND).build()

    /** The last actually-executed reload report. */
    @GET
    @Path("/last")
    @RolesAllowed(Roles.ADMIN, Roles.AUDITOR)
    fun last(): Response = service.lastReport()?.let { Response.ok(it).build() }
        ?: Response.status(Response.Status.NO_CONTENT).build()

    private fun parseRequired(value: String?, field: String): Instant =
        value?.let(Instant::parse) ?: throw badRequest("$field is required")

    private fun badRequest(message: String) =
        jakarta.ws.rs.WebApplicationException(message, Response.Status.BAD_REQUEST)
}
