// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.out.ReturnReport
import com.openbank.pension.application.usecase.StateContributionDeadlines
import com.openbank.pension.application.usecase.StateContributionReturnService
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

data class IneligibilityRequest(val ineligibleFrom: String? = null)

data class ReturnReportRequest(val month: String? = null)

data class ReturnResultRequest(val payload: String? = null)

data class StateContributionReturnResponse(
    val id: UUID,
    val contractId: UUID,
    val claimId: UUID?,
    val cause: String,
    val amount: BigDecimal,
    val currency: String,
    val discoveredOn: LocalDate,
    val dueBy: LocalDate,
    val status: String,
    val reportId: UUID?,
) {
    companion object {
        fun from(r: StateContributionReturn) = StateContributionReturnResponse(
            r.id, r.contractId, r.claimId, r.cause.name, r.amount, r.currency, r.discoveredOn, r.dueBy,
            r.status.name, r.reportId,
        )
    }
}

data class ReturnReportResponse(
    val id: UUID,
    val month: String,
    val returnIds: List<UUID>,
    val payload: String,
    val channelReference: String?,
    val resultApplied: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(r: ReturnReport) = ReturnReportResponse(
            r.id,
            r.month.toString(),
            r.returnIds,
            r.payload,
            r.channelReference,
            r.resultApplied,
            r.createdAt,
        )
    }
}

/** `filed` is false when no return was DUE, so no report was filed. */
data class ReturnReportRunResponse(val filed: Boolean, val report: ReturnReportResponse?)

/**
 * Operator routes for the CZ state-contribution returns (ZDPS §18, #12382). Like the other funding
 * operations, these are staff only, with no `ROLE_API`, so the customer edge cannot reach them.
 * Every POST requires an `Idempotency-Key`, which `IdempotencyReplayFilter` replays.
 */
@Tag(
    name = "Pension funding operations",
    description = "Operator queue, employer batches and state incentive claim batches",
)
@Path("/api/v1/pension/funding/operations/state-contribution")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.PAYMENTS)
class StateContributionOperationsResource {

    @Inject
    lateinit var returns: StateContributionReturnService

    @POST
    @Path("/contracts/{contractId}/ineligibility")
    @Operation(summary = "Record that the participant was not entitled from a month on; registers the returns owed")
    @Authorize(action = "pension.funding.operate", resource = "#contractId")
    suspend fun ineligibility(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        request: IneligibilityRequest?,
    ): List<StateContributionReturnResponse> {
        requireIdempotencyKey(idempotencyKey)
        val from =
            month(requireNotNull(request?.ineligibleFrom) { "ineligibleFrom (YYYY-MM) is required" }, "ineligibleFrom")
        return returns.registerIneligibility(contractId, from).map(StateContributionReturnResponse::from)
    }

    @GET
    @Path("/returns")
    @Operation(summary = "State contribution returns, optionally by status")
    @Authorize(action = "pension.funding.operate")
    suspend fun list(@QueryParam("status") status: String?): List<StateContributionReturnResponse> {
        val parsed = status?.let { s ->
            ReturnStatus.entries.firstOrNull { it.name == s } ?: throw IllegalArgumentException("unknown status '$s'")
        }
        return returns.list(parsed).map(StateContributionReturnResponse::from)
    }

    @POST
    @Path("/returns/{id}/settle")
    @Operation(summary = "Record that a confirmed return was paid back to the agency")
    @Authorize(action = "pension.funding.operate", resource = "#id")
    suspend fun settle(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
    ): StateContributionReturnResponse {
        requireIdempotencyKey(idempotencyKey)
        return StateContributionReturnResponse.from(returns.settle(id))
    }

    @POST
    @Path("/return-reports")
    @Operation(summary = "File the monthly return report with every DUE return")
    @Authorize(action = "pension.funding.operate")
    suspend fun fileReport(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: ReturnReportRequest?,
    ): ReturnReportRunResponse {
        requireIdempotencyKey(idempotencyKey)
        val m = month(requireNotNull(request?.month) { "month (YYYY-MM) is required" }, "month")
        val report = returns.fileReturnReport(m)
        return ReturnReportRunResponse(report != null, report?.let(ReturnReportResponse::from))
    }

    @GET
    @Path("/return-reports")
    @Operation(summary = "Filed return reports, newest first, with their filed payload")
    @Authorize(action = "pension.funding.operate")
    suspend fun reports(): List<ReturnReportResponse> = returns.reports().map(ReturnReportResponse::from)

    @POST
    @Path("/return-reports/{id}/result")
    @Operation(summary = "Apply the agency's result to a return report")
    @Authorize(action = "pension.funding.operate", resource = "#id")
    suspend fun result(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
        request: ReturnResultRequest?,
    ): ReturnReportResponse {
        requireIdempotencyKey(idempotencyKey)
        val payload = requireNotNull(request?.payload?.takeIf { it.isNotBlank() }) { "payload is required" }
        return ReturnReportResponse.from(returns.applyReturnResult(id, payload))
    }

    @GET
    @Path("/deadlines")
    @Operation(summary = "Missed state contribution deadlines (filing, payment, returns)")
    @Authorize(action = "pension.funding.operate")
    suspend fun deadlines(): StateContributionDeadlines = returns.deadlines()

    private fun month(raw: String, name: String): YearMonth = try {
        YearMonth.parse(raw)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("$name must be YYYY-MM", e)
    }
}
