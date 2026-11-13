// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

import com.openbank.aml.application.report.DecisionOutcomes
import com.openbank.aml.application.report.MonitoringDispositionService
import com.openbank.aml.domain.report.CaseDisposition
import com.openbank.aml.infrastructure.rest.dto.DecideDispositionRequest
import com.openbank.aml.infrastructure.rest.dto.OutcomeCatalogueResponse
import com.openbank.aml.infrastructure.rest.dto.OutcomeEntryResponse
import com.openbank.aml.infrastructure.rest.dto.ProposeDispositionRequest
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
 * Four-eyes disposition of transaction-monitoring cases (ADR-0371 §5).
 *
 * Compliance role only, and each method carries its own `@Authorize`: the interceptor reads the
 * annotation from the method, so a class-level one would authorize nothing. The acting person is
 * always the authenticated principal.
 */
@Path("/api/v1/aml/monitoring")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AML Monitoring", description = "Four-eyes disposition of transaction-monitoring cases")
class AmlMonitoringDispositionResource(private val dispositions: MonitoringDispositionService) {

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var outcomes: DecisionOutcomes

    @POST
    @Path("/cases/{caseId}/disposition-proposals")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlMonitoring.propose", resource = "#caseId")
    @Operation(summary = "Propose how a transaction-monitoring case is closed")
    suspend fun propose(@PathParam("caseId") caseId: UUID, request: ProposeDispositionRequest?): Response {
        requireNotNull(request) { "request body is required" }
        val disposition = CaseDisposition.entries.firstOrNull { it.name == request.disposition }
            ?: throw IllegalArgumentException("disposition must be one of ${CaseDisposition.entries.map { it.name }}")
        val proposal = dispositions.propose(
            caseId,
            disposition,
            request.outcomeCode,
            request.note,
            identity.principal.name,
        )
        return Response.created(URI.create("/api/v1/aml/monitoring/disposition-proposals/${proposal.id}"))
            .entity(proposal.toResponse())
            .build()
    }

    @POST
    @Path("/disposition-proposals/{proposalId}/decision")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlMonitoring.decide", resource = "#proposalId")
    @Operation(summary = "Approve or reject a disposition proposal as a second, different person")
    suspend fun decide(@PathParam("proposalId") proposalId: UUID, request: DecideDispositionRequest?): Response {
        val approve = requireNotNull(request?.approve) { "approve is required" }
        return Response.ok(
            dispositions.decide(proposalId, approve, request?.reason, identity.principal.name).toResponse(),
        ).build()
    }

    @GET
    @Path("/outcomes")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlMonitoring.inspect")
    @Operation(summary = "The outcome vocabulary a disposition or a report outcome is chosen from")
    fun outcomes(): Response {
        val ref = outcomes.reference
        return Response.ok(
            OutcomeCatalogueResponse(
                vocabularyId = ref.id,
                vocabularyRevision = ref.revision,
                vocabularyHash = ref.contentHash,
                entries = outcomes.entries.map { OutcomeEntryResponse(it.code.value, it.label, it.meaning) },
            ),
        ).build()
    }

    @GET
    @Path("/disposition-proposals")
    @RolesAllowed("ROLE_COMPLIANCE")
    @Authorize(action = "amlMonitoring.inspect")
    @Operation(summary = "Disposition proposals awaiting a decision")
    suspend fun pending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response =
        Response.ok(dispositions.pending(limit).map { it.toResponse() }).build()
}
