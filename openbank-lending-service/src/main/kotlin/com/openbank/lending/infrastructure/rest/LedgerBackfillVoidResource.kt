// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.usecase.LedgerBackfillVoidService
import com.openbank.lending.application.usecase.VoidExecution
import com.openbank.lending.application.usecase.VoidRequestView
import com.openbank.libs.authz.Authorize
import com.openbank.libs.governance.MakerCheckerViolation
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
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
import java.util.UUID

/**
 * Four-eyes void of the synthetic loans an EXECUTED ledger backfill posted (#10969): they were never
 * paid out, so they are cancelled, not disbursed. The same four `lending.ledgerBackfill.*` OPA
 * actions as [LedgerBackfillResource], so `lending_rest_ext.rego` (keyed on that prefix) applies
 * unchanged: humans only, ROLE_FINANCE or ROLE_ADMIN, every service account vetoed.
 *
 * Operator flow: `GET /voids/plan?sourceRequestId=` (dry-run) -> `POST /voids` (maker) ->
 * `POST /voids/{id}/decide` (checker, must differ) -> `POST /voids/{id}/execute?execute=true`.
 */
@Path("/api/v1/lending/ledger-backfill/voids")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Ledger Backfill", description = "Four-eyes back-posting of loan GL history that never reached the ledger")
@RolesAllowed("ROLE_ADMIN", "ROLE_FINANCE")
class LedgerBackfillVoidResource(private val voids: LedgerBackfillVoidService, private val identity: SecurityIdentity) {
    private fun actor(): String = identity.principal?.name.orEmpty()

    @GET
    @Path("/plan")
    @Authorize(action = "lending.ledgerBackfill.read", resource = "")
    @Operation(
        summary = "Dry-run a void: every leg that would be offset for the source's ACTIVE loans (writes nothing)",
    )
    fun plan(@QueryParam("sourceRequestId") sourceRequestId: UUID?): Uni<Response> = guarded {
        requireNotNull(sourceRequestId) { "sourceRequestId is required" }
        voids.dryRun(sourceRequestId).map { Response.ok(it.toResponse()).build() }
    }

    @GET
    @Authorize(action = "lending.ledgerBackfill.read", resource = "")
    @Operation(summary = "Void request history, newest first (limit 1..100, default 25)")
    fun list(@QueryParam("limit") limit: Int?): Uni<Response> = guarded {
        val size = limit ?: DEFAULT_LIMIT
        require(size in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        voids.list(size).map { Response.ok(VoidRequestListResponse(it)).build() }
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "lending.ledgerBackfill.read", resource = "#id")
    @Operation(summary = "One void request with its four-eyes state")
    fun get(@PathParam("id") id: UUID): Uni<Response> = guarded {
        voids.get(id).map { view ->
            view?.let { Response.ok(it).build() }
                ?: error(HTTP_NOT_FOUND, IllegalArgumentException("Void request not found: $id"))
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "lending.ledgerBackfill.propose", resource = "")
    @Operation(summary = "Propose voiding an EXECUTED backfill's synthetic loans (maker)")
    fun propose(request: ProposeVoidRequest?): Uni<Response> = guarded {
        requireNotNull(request) { "request body is required" }
        val source = requireNotNull(request.sourceRequestId) { "sourceRequestId is required" }
        voids.propose(source, actor()).map { Response.status(HTTP_CREATED).entity(it).build() }
    }

    @POST
    @Path("/{id}/decide")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "lending.ledgerBackfill.decide", resource = "#id")
    @Operation(summary = "Approve or reject a void (checker, must differ from the maker)")
    fun decide(@PathParam("id") id: UUID, request: DecideBackfillRequest?): Uni<Response> = guarded {
        requireNotNull(request) { "request body is required" }
        requireNotNull(request.approve) { "approve is required" }
        voids.decide(id, request.approve, actor(), request.reason).map { Response.ok(it).build() }
    }

    @POST
    @Path("/{id}/execute")
    @Authorize(action = "lending.ledgerBackfill.execute", resource = "#id")
    @Operation(summary = "Execute an APPROVED void; without execute=true it only returns the plan")
    fun execute(@PathParam("id") id: UUID, @QueryParam("execute") execute: Boolean?): Uni<Response> = guarded {
        voids.execute(id, execute == true, actor()).map { Response.ok(it.toResponse()).build() }
    }

    /** 400 for input errors, 409 for state/hash refusals, 422 for a four-eyes violation. */
    private fun guarded(block: () -> Uni<Response>): Uni<Response> =
        runCatching(block).getOrElse { Uni.createFrom().failure(it) }
            .onFailure(MakerCheckerViolation::class.java)
            .recoverWithItem { e -> error(HTTP_UNPROCESSABLE, e) }
            .onFailure(IllegalArgumentException::class.java)
            .recoverWithItem { e -> error(HTTP_BAD_REQUEST, e) }
            .onFailure(IllegalStateException::class.java)
            .recoverWithItem { e -> error(HTTP_CONFLICT, e) }

    private fun error(status: Int, e: Throwable) = Response.status(status).entity(mapOf("error" to e.message)).build()

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val HTTP_CREATED = 201
        const val HTTP_NOT_FOUND = 404
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
    }
}

/**
 * A void's outcome. [offsetGlTotals] are the GL totals of the legs being offset; the mirror journals
 * book exactly their negation, so each loan's net GL effect after the void is zero.
 */
data class VoidExecutionResponse(val execution: VoidExecution, val offsetGlTotals: List<GlTotal>)

internal fun VoidExecution.toResponse() = VoidExecutionResponse(this, plan.glTotals())

data class VoidRequestListResponse(val requests: List<VoidRequestView>)

data class ProposeVoidRequest(val sourceRequestId: UUID? = null)
