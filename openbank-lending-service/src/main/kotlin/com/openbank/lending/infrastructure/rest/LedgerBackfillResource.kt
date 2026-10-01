// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.usecase.BackfillExecution
import com.openbank.lending.application.usecase.BackfillPlan
import com.openbank.lending.application.usecase.BackfillRequestView
import com.openbank.lending.application.usecase.BackfillScope
import com.openbank.lending.application.usecase.LedgerBackfillService
import com.openbank.lending.infrastructure.client.LendingGlChart
import com.openbank.lending.infrastructure.client.LendingJournalFactory
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.Money
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One-off ledger backfill for loans whose GL history never reached the ledger (#10746, root cause
 * #6057). ROLE_FINANCE (the finance department, #10618) or ROLE_ADMIN, humans only: OPA additionally
 * vetoes every service account and every human holding neither role (lending_rest_ext.rego).
 * Maker != checker is enforced in [LedgerBackfillService]; execution is open to either role because
 * the control is the approval, which is bound to the plan hash and re-checked at execution.
 *
 * Operator flow: `GET /plan` (dry-run, writes nothing) -> `POST /requests` (maker) ->
 * `POST /requests/{id}/decide` (checker, must differ) -> `POST /requests/{id}/execute?execute=true`.
 * Without `execute=true` the execute endpoint only returns the plan it would post.
 */
@Path("/api/v1/lending/ledger-backfill")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Ledger Backfill", description = "Four-eyes back-posting of loan GL history that never reached the ledger")
@RolesAllowed("ROLE_ADMIN", "ROLE_FINANCE")
class LedgerBackfillResource(private val backfill: LedgerBackfillService, private val identity: SecurityIdentity) {
    private fun actor(): String = identity.principal?.name.orEmpty()

    @GET
    @Path("/plan")
    @Authorize(action = "lending.ledgerBackfill.read", resource = "")
    @Operation(summary = "Dry-run: the exact journal set, GL totals and tie-out (writes nothing)")
    fun plan(
        @QueryParam("cutoverDate") cutoverDate: String?,
        @QueryParam("disbursedBefore") disbursedBefore: String?,
    ): Uni<Response> = guarded {
        backfill.dryRun(scope(cutoverDate, disbursedBefore)).map { Response.ok(it.toResponse()).build() }
    }

    @GET
    @Path("/requests")
    @Authorize(action = "lending.ledgerBackfill.read", resource = "")
    @Operation(summary = "Request history, newest first (limit 1..100, default 25)")
    fun list(@QueryParam("limit") limit: Int?): Uni<Response> = guarded {
        val size = limit ?: DEFAULT_LIMIT
        require(size in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        backfill.list(size).map { Response.ok(BackfillRequestListResponse(it)).build() }
    }

    @GET
    @Path("/requests/{id}")
    @Authorize(action = "lending.ledgerBackfill.read", resource = "#id")
    @Operation(summary = "One backfill request with its four-eyes state")
    fun get(@PathParam("id") id: UUID): Uni<Response> = guarded {
        backfill.get(id).map { view ->
            view?.let { Response.ok(it).build() }
                ?: error(HTTP_NOT_FOUND, IllegalArgumentException("Backfill request not found: $id"))
        }
    }

    @POST
    @Path("/requests")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "lending.ledgerBackfill.propose", resource = "")
    @Operation(summary = "Propose a backfill (maker); binds the approval to the plan hash")
    fun propose(request: ProposeBackfillRequest?): Uni<Response> = guarded {
        requireNotNull(request) { "request body is required" }
        backfill.propose(scope(request.cutoverDate, request.disbursedBefore), actor())
            .map { Response.status(HTTP_CREATED).entity(it).build() }
    }

    @POST
    @Path("/requests/{id}/decide")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "lending.ledgerBackfill.decide", resource = "#id")
    @Operation(summary = "Approve or reject a backfill request (checker, must differ from the maker)")
    fun decide(@PathParam("id") id: UUID, request: DecideBackfillRequest?): Uni<Response> = guarded {
        requireNotNull(request) { "request body is required" }
        requireNotNull(request.approve) { "approve is required" }
        backfill.decide(id, request.approve, actor(), request.reason).map { Response.ok(it).build() }
    }

    @POST
    @Path("/requests/{id}/execute")
    @Authorize(action = "lending.ledgerBackfill.execute", resource = "#id")
    @Operation(summary = "Execute an APPROVED backfill; without execute=true it only returns the plan")
    fun execute(@PathParam("id") id: UUID, @QueryParam("execute") execute: Boolean?): Uni<Response> = guarded {
        backfill.execute(id, execute == true, actor()).map { Response.ok(it.toResponse()).build() }
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

private fun scope(cutoverDate: String?, disbursedBefore: String?): BackfillScope {
    requireNotNull(cutoverDate) { "cutoverDate is required" }
    requireNotNull(disbursedBefore) { "disbursedBefore is required" }
    return BackfillScope(parseDate("cutoverDate", cutoverDate), parseDate("disbursedBefore", disbursedBefore))
}

private fun parseDate(name: String, value: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("$name must be an ISO date") }

data class BackfillRequestListResponse(val requests: List<BackfillRequestView>)

data class ProposeBackfillRequest(val cutoverDate: String? = null, val disbursedBefore: String? = null)

data class DecideBackfillRequest(val approve: Boolean? = null, val reason: String? = null)

/** One GL account's movement if the plan is posted. `net` is debit − credit. */
data class GlTotal(
    val code: String,
    val currency: String,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val net: BigDecimal,
)

data class BackfillPlanResponse(
    val plan: BackfillPlan,
    val executable: Boolean,
    val journalCount: Int,
    val glTotals: List<GlTotal>,
)

data class BackfillExecutionResponse(val execution: BackfillExecution, val glTotals: List<GlTotal>)

internal fun BackfillPlan.glTotals(): List<GlTotal> {
    val rows = legs.flatMap { leg ->
        val posting = LedgerPosting(
            leg.reference,
            UUID(0, 0),
            Money.of(leg.amount, leg.currency),
            leg.kind,
        )
        LendingJournalFactory.buildLines(posting, LendingGlChart.accountsFor(leg.currency))
    }
    return rows.groupBy { LendingGlChart.codeOf(it.glAccountId) to it.currencyCode }
        .map { (key, lines) ->
            val debit = lines.filter { it.side == "DEBIT" }.sumOf { it.amount }
            val credit = lines.filter { it.side == "CREDIT" }.sumOf { it.amount }
            GlTotal(key.first, key.second, debit, credit, debit - credit)
        }
        .sortedWith(compareBy({ it.code }, { it.currency }))
}

internal fun BackfillPlan.toResponse() = BackfillPlanResponse(this, executable, legs.size, glTotals())

internal fun BackfillExecution.toResponse() = BackfillExecutionResponse(this, plan.glTotals())
