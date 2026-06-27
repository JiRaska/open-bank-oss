// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.pid.application.port.`in`.DecideCaseCommand
import com.openbank.pid.application.port.`in`.ManageVerificationCaseUseCase
import com.openbank.pid.application.port.`in`.ReopenCaseCommand
import com.openbank.pid.application.usecase.VerificationCaseNotFoundException
import com.openbank.pid.infrastructure.rest.dto.CaseDecisionRequest
import com.openbank.pid.infrastructure.rest.dto.toResponse
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Four-eyes identity-verification cockpit (ADR-0072 §1 / ADR-0030).
 *
 * Operators list ambiguous cases opened by /resolve and decide them with two distinct approvers.
 * The acting approver is the authenticated principal (never client-supplied), so a single user
 * cannot satisfy both votes. A DECIDED case steers subsequent /resolve calls for the applicant.
 */
@Path("/api/v1/parties/cases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Identity verification cases", description = "Four-eyes adjudication of ambiguous identities (ADR-0072)")
class VerificationCaseResource(
    private val manageCases: ManageVerificationCaseUseCase,
    private val securityIdentity: SecurityIdentity,
) {

    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "identity.case.list")
    @Operation(summary = "List active (OPEN + awaiting) identity-verification cases")
    suspend fun listActive(): Response = Response.ok(manageCases.listActive().map { it.toResponse() }).build()

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "identity.case.get")
    @Operation(summary = "Get a single identity-verification case")
    suspend fun get(@PathParam("id") id: UUID): Response {
        val case = manageCases.get(id)
            ?: throw VerificationCaseNotFoundException("verification case $id not found")
        return Response.ok(case.toResponse()).build()
    }

    @POST
    @Path("/{id}/decision")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "identity.case.decide")
    @Operation(
        summary = "Record an approver's verdict (first vote opens awaiting; a distinct concurring vote decides)",
    )
    suspend fun decide(@PathParam("id") id: UUID, request: CaseDecisionRequest): Response {
        val updated = manageCases.decide(
            DecideCaseCommand(
                caseId = id,
                approver = actingApprover(),
                verdict = request.verdict,
                linkPartyId = request.linkPartyId,
                notes = request.notes,
            ),
        )
        return Response.ok(updated.toResponse()).build()
    }

    @POST
    @Path("/{id}/reopen")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "identity.case.reopen")
    @Operation(summary = "Withdraw an awaiting first proposal, returning the case to OPEN")
    suspend fun reopen(@PathParam("id") id: UUID): Response {
        val updated = manageCases.reopen(ReopenCaseCommand(caseId = id, actor = actingApprover()))
        return Response.ok(updated.toResponse()).build()
    }

    /** The authenticated principal acting on the case — the four-eyes identity, never client-supplied. */
    private fun actingApprover(): String = securityIdentity.principal?.name?.takeIf { it.isNotBlank() } ?: "unknown"
}
