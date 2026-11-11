// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

import com.openbank.aml.application.report.SuspiciousTransactionReportService
import com.openbank.aml.domain.report.ReportState
import com.openbank.aml.domain.report.ReportedMovement
import com.openbank.aml.infrastructure.rest.dto.DraftReportRequest
import com.openbank.aml.infrastructure.rest.dto.RecordFilingRequest
import com.openbank.aml.infrastructure.rest.dto.WithdrawReportRequest
import com.openbank.aml.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import java.net.URI
import java.util.UUID

/**
 * The suspicious-transaction report (ADR-0371 §6, §7).
 *
 * Compliance role only; an operator, a customer and every machine principal are refused. The read
 * actions are named `inspect`, not `read`/`list`, on purpose: the fleet policy grants every operator
 * any action ending in `.read` or `.list`, and a report must not ride on that grant.
 */
@Path("/api/v1/aml/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AML Reports", description = "Suspicious-transaction report lifecycle")
class AmlReportResource(private val reports: SuspiciousTransactionReportService) {

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlReport.draft")
    @Operation(summary = "Draft a suspicious-transaction report")
    suspend fun draft(request: DraftReportRequest?): Response {
        requireNotNull(request) { "request body is required" }
        val movements = request.movements.orEmpty().map {
            ReportedMovement(
                requireNotNull(it.journalEntryId) { "movement journalEntryId is required" },
                requireNotNull(it.accountId) { "movement accountId is required" },
                requireNotNull(it.currency) { "movement currency is required" },
            )
        }
        val report = reports.draft(
            partyId = requireNotNull(request.partyId) { "partyId is required" },
            narrative = requireNotNull(request.narrative) { "narrative is required" },
            caseIds = request.caseIds.orEmpty(),
            movements = movements,
            drafter = identity.principal.name,
        )
        return Response.created(URI.create("/api/v1/aml/reports/${report.id}")).entity(report.toResponse()).build()
    }

    @GET
    @Path("/{reportId}")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlReport.inspect", resource = "#reportId")
    @Operation(summary = "Get a suspicious-transaction report")
    suspend fun get(@PathParam("reportId") reportId: UUID): Response =
        Response.ok(reports.get(reportId).toResponse()).build()

    @GET
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlReport.inspect")
    @Operation(summary = "List suspicious-transaction reports")
    suspend fun list(
        @QueryParam("state") state: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): Response {
        val filter = state?.let { wanted ->
            ReportState.entries.firstOrNull { it.name == wanted }
                ?: throw IllegalArgumentException("unknown report state")
        }
        return Response.ok(reports.list(filter, limit).map { it.toResponse() }).build()
    }

    @POST
    @Path("/{reportId}/approval")
    // No request body: without this the class-level JSON @Consumes answers 415 to a bare POST.
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlReport.approve", resource = "#reportId")
    @Operation(summary = "Approve a drafted report as a second, different person")
    suspend fun approve(@PathParam("reportId") reportId: UUID): Response =
        Response.ok(reports.approve(reportId, identity.principal.name).toResponse()).build()

    @POST
    @Path("/{reportId}/filing")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlReport.file", resource = "#reportId")
    @Operation(summary = "Record that an approved report was filed outside this system")
    suspend fun recordFiling(@PathParam("reportId") reportId: UUID, request: RecordFilingRequest?): Response =
        Response.ok(
            reports.recordFiling(
                reportId,
                request?.filingReference,
                request?.filedAt,
                request?.outcomeCode,
                identity.principal.name,
            )
                .toResponse(),
        ).build()

    @POST
    @Path("/{reportId}/withdrawal")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlReport.withdraw", resource = "#reportId")
    @Operation(summary = "Withdraw a report that was not filed")
    suspend fun withdraw(@PathParam("reportId") reportId: UUID, request: WithdrawReportRequest?): Response =
        Response.ok(reports.withdraw(reportId, request?.outcomeCode, identity.principal.name).toResponse()).build()
}
