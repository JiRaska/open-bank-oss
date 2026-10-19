// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.out.RevaluationRecord
import com.openbank.treasury.application.usecase.RevaluationService
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D7/D8 revaluation: the on-demand run (back office) and the record of one date. The
 * daily run is `RevaluationScheduler`. `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Treasury", description = "Treasury fair-value revaluation (ADR-0344 D7/D8)")
@Path("/api/v1/treasury/revaluations")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE)
class RevaluationResource {

    @Inject
    lateinit var revaluation: RevaluationService

    @GET
    @Operation(summary = "The revaluations recorded for one valuation date (limit 1..1000, default 500)")
    @Authorize(action = "treasury.revaluation.read")
    suspend fun list(@QueryParam("asOf") asOf: String?, @QueryParam("limit") limit: Int?): List<RevaluationView> =
        revaluation.records(date(asOf), limit ?: DEFAULT_LIMIT).map { it.view() }

    @POST
    @Path("/run")
    @RolesAllowed(BACK_OFFICE)
    @Operation(
        summary = "Revalue the fair-value deals (or one, by dealId) off the TIED_OUT valuation of the date",
    )
    @Authorize(action = "treasury.revaluation.run")
    suspend fun run(@QueryParam("asOf") asOf: String?, @QueryParam("dealId") dealId: String?): Map<String, Any?> {
        val deal = dealId?.let {
            runCatching { UUID.fromString(it) }.getOrElse { throw IllegalArgumentException("dealId must be a UUID") }
        }
        val r = revaluation.revalue(date(asOf), deal)
        return mapOf(
            "asOf" to r.asOf,
            "snapshotId" to r.snapshotId,
            "revalued" to r.revalued.map { it.view() },
            "journals" to r.journals,
            "gaps" to r.gaps,
            "failures" to r.failures.map { it.message.orEmpty().take(MAX_MESSAGE) },
        )
    }

    private fun date(raw: String?): LocalDate = runCatching {
        LocalDate.parse(requireNotNull(raw) { "query parameter 'asOf' is required" })
    }.getOrElse { throw IllegalArgumentException("'asOf' must be an ISO date (yyyy-MM-dd)", it) }

    private companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_MESSAGE = 500
    }
}

data class RevaluationView(
    val dealId: UUID,
    val asOf: LocalDate,
    val snapshotId: UUID,
    val fairValue: java.math.BigDecimal,
    val carrying: java.math.BigDecimal,
    val cumulative: java.math.BigDecimal,
    val change: java.math.BigDecimal,
    val journalId: UUID?,
)

private fun RevaluationRecord.view() =
    RevaluationView(dealId, asOf, snapshotId, fairValue, carrying, cumulative, change, journalId)
