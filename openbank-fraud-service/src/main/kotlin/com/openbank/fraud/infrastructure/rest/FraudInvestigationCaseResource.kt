// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.application.port.out.FraudInvestigationCaseStore
import com.openbank.fraud.domain.model.FraudInvestigationCase
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Source-owned investigation roots. These operations do not change payment execution or fraud verdicts. */
@Path("/api/v1/fraud/cases")
@Produces(MediaType.APPLICATION_JSON)
class FraudInvestigationCaseResource(
    private val cases: FraudInvestigationCaseStore,
    private val identity: SecurityIdentity,
) {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "fraud.case.open")
    @Operation(summary = "Open a human-reviewed investigation from an existing REVIEW score")
    suspend fun open(
        request: OpenFraudInvestigationRequest?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        val scoreId = requireNotNull(request?.scoreId) { "scoreId is required" }
        val result = cases.open(scoreId, identity.principal.name)
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.created(URI.create("/api/v1/fraud/cases/${result.id}"))
            .entity(result.toResponse()).header("Cache-Control", "no-store").build()
    }

    @GET
    @Path("/{caseId}")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "fraud.case.read", resource = "#caseId")
    @Operation(summary = "Read the current fraud investigation status for an administrator")
    suspend fun get(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        val result = cases.find(caseId) ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(result.toResponse()).header("Cache-Control", "no-store").build()
    }

    @POST
    @Path("/{caseId}/close-without-finding")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "fraud.case.close", resource = "#caseId")
    @Operation(summary = "Close an investigation without asserting a fraud finding")
    suspend fun closeWithoutFinding(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        val result = cases.closeWithoutFinding(caseId, identity.principal.name)
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(result.toResponse()).header("Cache-Control", "no-store").build()
    }

    private companion object {
        const val PURPOSE = "FRAUD_INVESTIGATION"
    }
}

data class OpenFraudInvestigationRequest(val scoreId: UUID?)

/** No account, counterparty, score amount, rule reasons or analyst identity leaves this endpoint. */
data class FraudInvestigationCaseResponse(
    val caseId: UUID,
    val scoreId: UUID,
    val status: String,
    val revision: Long,
    val openedAt: Instant,
    val closedAt: Instant?,
)

private fun FraudInvestigationCase.toResponse() = FraudInvestigationCaseResponse(
    id,
    scoreId,
    status.name,
    revision,
    openedAt,
    closedAt,
)
