// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.application.port.out.FraudCaseAccessDecision
import com.openbank.fraud.application.port.out.FraudCaseContextAccess
import com.openbank.fraud.application.port.out.FraudInvestigationCaseStore
import com.openbank.fraud.domain.model.FraudInvestigationCase
import com.openbank.fraud.domain.model.FraudInvestigationStatus
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
    private val contextAccess: FraudCaseContextAccess,
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
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key is required" }
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
    @Operation(summary = "Read current fraud investigation status after live case-scoped authorization")
    suspend fun get(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @HeaderParam("Authorization") authorization: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        val accessFailure = checkCaseAccess(caseId, authorization)
        if (accessFailure != null) return accessFailure
        val result = cases.find(caseId) ?: return Response.status(Response.Status.NOT_FOUND)
            .header("Cache-Control", "no-store").build()
        return Response.ok(result.toResponse()).header("Cache-Control", "no-store").build()
    }

    @GET
    @Path("/{caseId}/evidence")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "fraud.case.read", resource = "#caseId")
    @Operation(summary = "Read source-owned case links after live, root-scoped Context authorization")
    suspend fun evidence(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @HeaderParam("Authorization") authorization: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        val accessFailure = checkCaseAccess(caseId, authorization)
        if (accessFailure != null) return accessFailure
        val result = cases.find(caseId) ?: return Response.status(Response.Status.NOT_FOUND)
            .header("Cache-Control", "no-store").build()
        if (result.status != FraudInvestigationStatus.OPEN) {
            return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        }
        return Response.ok(result.toEvidence()).header("Cache-Control", "no-store").build()
    }

    @GET
    @Path("/{caseId}/match-assigned")
    @RolesAllowed("ROLE_CONTEXT_INVESTIGATION")
    @Operation(summary = "Find source-equal open cases within an already assigned candidate set")
    suspend fun matchAssigned(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @HeaderParam("X-Investigator-Authorization") investigatorAuthorization: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        if (identity.principal.name != CONTEXT_SERVICE_PRINCIPAL) {
            return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        }
        val humanBearer = investigatorAuthorization?.takeIf {
            it.startsWith("Bearer ") && it.length > "Bearer ".length
        } ?: return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        val candidates = contextAccess.assignedCandidates(caseId, humanBearer)
            ?: return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        val root = cases.find(caseId) ?: return Response.status(Response.Status.NOT_FOUND)
            .header("Cache-Control", "no-store").build()
        if (root.status != FraudInvestigationStatus.OPEN) {
            return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        }
        val matches = cases.matchingAssigned(caseId, candidates.ids)
        return Response.ok(
            FraudAssignedMatchResponse(
                matches.take(MAX_MATCHES),
                candidates.ids.size,
                candidates.truncated || matches.size > MAX_MATCHES,
            ),
        )
            .header("Cache-Control", "no-store").build()
    }

    private suspend fun checkCaseAccess(caseId: UUID, authorization: String?): Response? {
        val bearer = authorization?.takeIf { it.startsWith("Bearer ") && it.length > "Bearer ".length }
            ?: return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        when (contextAccess.check(caseId, bearer)) {
            FraudCaseAccessDecision.DENIED ->
                return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
            FraudCaseAccessDecision.UNAVAILABLE ->
                return Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
            FraudCaseAccessDecision.ALLOWED -> return null
        }
    }

    @POST
    @Path("/{caseId}/close-without-finding")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "fraud.case.close", resource = "#caseId")
    @Operation(summary = "Close an assigned investigation without asserting a fraud finding")
    suspend fun closeWithoutFinding(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("Authorization") authorization: String?,
    ): Response {
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key is required" }
        val accessFailure = checkCaseAccess(caseId, authorization)
        if (accessFailure != null) return accessFailure
        val result = cases.closeWithoutFinding(caseId, identity.principal.name)
            ?: return Response.status(Response.Status.NOT_FOUND).header("Cache-Control", "no-store").build()
        return Response.ok(result.toResponse()).header("Cache-Control", "no-store").build()
    }

    private companion object {
        const val PURPOSE = "FRAUD_INVESTIGATION"
        const val CONTEXT_SERVICE_PRINCIPAL = "service-account-openbank-context-investigation"
        const val MAX_MATCHES = 4
    }
}

data class OpenFraudInvestigationRequest(val scoreId: UUID?)

data class FraudAssignedMatchResponse(
    val candidateIds: List<UUID>,
    val inspectedCandidates: Int,
    val truncated: Boolean,
)

/** No account, counterparty, score amount, rule reasons or analyst identity leaves this endpoint. */
data class FraudInvestigationCaseResponse(
    val caseId: UUID,
    val scoreId: UUID,
    val status: String,
    val revision: Long,
    val openedAt: Instant,
    val closedAt: Instant?,
)

/** Reviewed score associations are evidence leads; this response never asserts a finding. */
data class FraudInvestigationEvidenceResponse(
    val caseId: UUID,
    val scoreId: UUID,
    val accountId: UUID,
    val counterpartyId: UUID?,
    val status: String,
    val revision: Long,
    val openedAt: Instant,
    val closedAt: Instant?,
)

private fun FraudInvestigationCase.toEvidence() = FraudInvestigationEvidenceResponse(
    id,
    scoreId,
    accountId,
    counterpartyId,
    status.name,
    revision,
    openedAt,
    closedAt,
)

private fun FraudInvestigationCase.toResponse() = FraudInvestigationCaseResponse(
    id,
    scoreId,
    status.name,
    revision,
    openedAt,
    closedAt,
)
