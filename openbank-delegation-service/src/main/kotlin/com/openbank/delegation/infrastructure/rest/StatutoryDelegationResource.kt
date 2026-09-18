// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.usecase.StatutoryDelegationCancellationService
import com.openbank.delegation.application.usecase.StatutoryDelegationDecisionService
import com.openbank.delegation.application.usecase.StatutoryDelegationExecutionService
import com.openbank.delegation.application.usecase.StatutoryDelegationProgressService
import com.openbank.delegation.application.usecase.StatutoryDelegationProposalService
import com.openbank.delegation.application.usecase.StatutorySigningProgress
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.infrastructure.rest.dto.DelegationResponse
import com.openbank.delegation.infrastructure.rest.dto.PreviewDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.toCommand
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

data class StatutoryProposalResponse(
    val id: UUID,
    val principalPartyId: UUID,
    val initiatorPartyId: UUID,
    val requestHash: String,
    val payload: JsonNode,
    val ruleHash: String,
    val rule: JsonNode,
    val state: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val grantId: UUID?,
    val canCancel: Boolean,
) {
    companion object {
        fun from(operation: StatutoryDelegationOperation, mapper: ObjectMapper, actor: UUID?) =
            StatutoryProposalResponse(
                id = operation.id,
                principalPartyId = operation.principalPartyId,
                initiatorPartyId = operation.initiatorPartyId,
                requestHash = operation.requestHash,
                payload = mapper.readTree(operation.payloadJson),
                ruleHash = operation.ruleHash,
                rule = mapper.readTree(operation.ruleSnapshotJson),
                state = operation.state.name,
                createdAt = operation.createdAt,
                expiresAt = operation.expiresAt,
                grantId = operation.grantId,
                canCancel = operation.state == StatutoryOperationState.PENDING && operation.initiatorPartyId == actor,
            )
    }
}

data class StatutoryApprovalIntentResponse(val operationId: UUID, val purpose: String, val operationHash: String)

data class StatutoryDecisionRequest(val verdict: StatutoryDecisionVerdict?, val scaSessionId: UUID? = null)

data class StatutoryDecisionResponse(
    val operationId: UUID,
    val actorPartyId: UUID,
    val verdict: StatutoryDecisionVerdict,
    val scaSessionId: UUID?,
    val decidedAt: Instant,
) {
    companion object {
        fun from(decision: StatutoryDelegationDecision) = StatutoryDecisionResponse(
            decision.operationId,
            decision.actorPartyId,
            decision.verdict,
            decision.scaSessionId,
            decision.decidedAt,
        )
    }
}

data class StatutoryProposalPageResponse(val items: List<StatutoryProposalResponse>, val nextCursor: String?)

data class StatutoryDecisionSummaryResponse(
    val actorPartyId: UUID,
    val verdict: StatutoryDecisionVerdict,
    val decidedAt: Instant,
)

/** Customer-edge-only JOINT proposal surface. PENDING is evidence, never a usable delegation. */
@Path("/api/v1/delegations/statutory-operations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
@Suppress("TooManyFunctions") // One resource owns the JOINT proposal lifecycle under one principal scope.
class StatutoryDelegationResource(
    private val service: StatutoryDelegationProposalService,
    private val cancellation: StatutoryDelegationCancellationService,
    private val decisions: StatutoryDelegationDecisionService,
    private val execution: StatutoryDelegationExecutionService,
    private val progress: StatutoryDelegationProgressService,
    private val mapper: ObjectMapper,
    private val identity: SecurityIdentity,
) {
    @POST
    @Path("/{id}/cancel")
    @Authorize(action = "delegation.statutory.cancel", resource = "#id")
    suspend fun cancel(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryProposalResponse {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        return StatutoryProposalResponse.from(
            cancellation.cancel(id, principal, customerPartyId, actorPartyId, StatutoryOperationKind.ISSUE),
            mapper,
            actorPartyId,
        )
    }

    @GET
    @Authorize(action = "delegation.statutory.read", resource = "#customerPartyId")
    suspend fun pending(
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): List<StatutoryProposalResponse> {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        return service.pending(principal, customerPartyId, actorPartyId)
            .map { StatutoryProposalResponse.from(it, mapper, actorPartyId) }
    }

    @GET
    @Path("/pages")
    @Authorize(action = "delegation.statutory.read", resource = "#customerPartyId")
    suspend fun page(
        @QueryParam("cursor") cursor: String?,
        @QueryParam("limit") limit: Int?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryProposalPageResponse {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        val result = service.page(principal, customerPartyId, actorPartyId, cursor, limit)
        return StatutoryProposalPageResponse(
            result.operations.map { StatutoryProposalResponse.from(it, mapper, actorPartyId) },
            result.nextCursor,
        )
    }

    @POST
    @Authorize(action = "delegation.statutory.propose", resource = "#request.grantorPartyId")
    suspend fun propose(
        request: PreviewDelegationRequest?,
        @HeaderParam("Idempotency-Key") requestKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): Response {
        requireEdge()
        requireNotNull(request) { "request body is required" }
        require(!requestKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val result = service.propose(request.toCommand(customerPartyId, actorPartyId), requestKey)
        val response = StatutoryProposalResponse.from(result.operation, mapper, actorPartyId)
        val status = when (result) {
            is StatutoryOperationCreateOutcome.Created -> Response.Status.CREATED
            is StatutoryOperationCreateOutcome.Replayed -> Response.Status.OK
        }
        return Response.status(status)
            .entity(response)
            .header("X-Idempotency-Replayed", result is StatutoryOperationCreateOutcome.Replayed)
            .build()
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.statutory.read", resource = "#id")
    suspend fun get(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryProposalResponse {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        return StatutoryProposalResponse.from(
            service.get(id, principal, customerPartyId, actorPartyId),
            mapper,
            actorPartyId,
        )
    }

    @GET
    @Path("/{id}/approval-intent")
    @Authorize(action = "delegation.statutory.intent", resource = "#id")
    suspend fun approvalIntent(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryApprovalIntentResponse {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return StatutoryApprovalIntentResponse(
            id,
            "DELEGATION_STATUTORY_APPROVAL",
            decisions.approvalIntent(id, principal, actor),
        )
    }

    @POST
    @Path("/{id}/decisions")
    @Authorize(action = "delegation.statutory.decide", resource = "#id")
    suspend fun decide(
        @PathParam("id") id: UUID,
        request: StatutoryDecisionRequest?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryDecisionResponse {
        requireEdge()
        val body = requireNotNull(request) { "decision body is required" }
        val verdict = requireNotNull(body.verdict) { "verdict is required" }
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return StatutoryDecisionResponse.from(decisions.decide(id, principal, actor, verdict, body.scaSessionId))
    }

    @GET
    @Path("/{id}/decisions")
    @Authorize(action = "delegation.statutory.read", resource = "#id")
    suspend fun decisionSummaries(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): List<StatutoryDecisionSummaryResponse> {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        return service.decisions(id, principal, customerPartyId, actorPartyId)
            .map { StatutoryDecisionSummaryResponse(it.actorPartyId, it.verdict, it.decidedAt) }
    }

    @GET
    @Path("/{id}/progress")
    @Authorize(action = "delegation.statutory.read", resource = "#id")
    suspend fun signingProgress(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutorySigningProgress {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return progress.get(id, principal, actor)
    }

    @POST
    @Path("/{id}/execute")
    @Authorize(action = "delegation.statutory.execute", resource = "#id")
    suspend fun execute(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): DelegationResponse {
        requireEdge()
        val principal = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return DelegationResponse.from(execution.execute(id, principal, actor))
    }

    private fun requireEdge() {
        if (identity.principal.name != "service-account-openbank-edge") {
            throw ForbiddenException("statutory proposals require the authenticated customer edge")
        }
    }
}
