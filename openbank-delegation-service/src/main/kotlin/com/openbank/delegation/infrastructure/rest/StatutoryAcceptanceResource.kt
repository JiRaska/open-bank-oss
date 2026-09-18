// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.usecase.StatutoryDelegationAcceptanceDecisionService
import com.openbank.delegation.application.usecase.StatutoryDelegationAcceptanceExecutionService
import com.openbank.delegation.application.usecase.StatutoryDelegationAcceptanceProgressService
import com.openbank.delegation.application.usecase.StatutoryDelegationAcceptanceProposalService
import com.openbank.delegation.application.usecase.StatutoryDelegationCancellationService
import com.openbank.delegation.application.usecase.StatutorySigningProgress
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.infrastructure.rest.dto.DelegationResponse
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

data class StatutoryAcceptanceResponse(
    val id: UUID,
    val principalPartyId: UUID,
    val initiatorPartyId: UUID,
    val targetGrantId: UUID,
    val expectedLifecycleRevision: Long,
    val requestHash: String,
    val offer: JsonNode,
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
            StatutoryAcceptanceResponse(
                id = operation.id,
                principalPartyId = operation.principalPartyId,
                initiatorPartyId = operation.initiatorPartyId,
                targetGrantId = requireNotNull(operation.targetGrantId),
                expectedLifecycleRevision = requireNotNull(operation.expectedLifecycleRevision),
                requestHash = operation.requestHash,
                offer = mapper.readTree(operation.payloadJson),
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

data class StatutoryAcceptancePageResponse(val items: List<StatutoryAcceptanceResponse>, val nextCursor: String?)

/** Company-grantee acceptance is a separate operation family from grantor issuance. */
@Path("/api/v1/delegations/statutory-acceptances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class StatutoryAcceptanceResource(
    private val proposals: StatutoryDelegationAcceptanceProposalService,
    private val cancellation: StatutoryDelegationCancellationService,
    private val decisions: StatutoryDelegationAcceptanceDecisionService,
    private val progress: StatutoryDelegationAcceptanceProgressService,
    private val execution: StatutoryDelegationAcceptanceExecutionService,
    private val mapper: ObjectMapper,
    private val identity: SecurityIdentity,
) {
    @POST
    @Path("/{id}/cancel")
    @Authorize(action = "delegation.statutory.accept.cancel", resource = "#id")
    suspend fun cancel(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryAcceptanceResponse {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        return StatutoryAcceptanceResponse.from(
            cancellation.cancel(id, company, customerPartyId, actorPartyId, StatutoryOperationKind.ACCEPT),
            mapper,
            actorPartyId,
        )
    }

    @GET
    @Path("/pages")
    @Authorize(action = "delegation.statutory.accept.read", resource = "#customerPartyId")
    suspend fun page(
        @QueryParam("cursor") cursor: String?,
        @QueryParam("limit") limit: Int?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryAcceptancePageResponse {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        val result = proposals.page(company, customerPartyId, actorPartyId, cursor, limit)
        return StatutoryAcceptancePageResponse(
            result.operations.map { StatutoryAcceptanceResponse.from(it, mapper, actorPartyId) },
            result.nextCursor,
        )
    }

    @POST
    @Path("/for-grant/{grantId}")
    @Authorize(action = "delegation.statutory.accept.propose", resource = "#grantId")
    suspend fun propose(
        @PathParam("grantId") grantId: UUID,
        @HeaderParam("Idempotency-Key") requestKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): Response {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        val key = requireNotNull(requestKey) { "Idempotency-Key header is required" }
        val result = proposals.propose(grantId, company, customerPartyId, actorPartyId, key)
        val status = when (result) {
            is StatutoryOperationCreateOutcome.Created -> Response.Status.CREATED
            is StatutoryOperationCreateOutcome.Replayed -> Response.Status.OK
        }
        return Response.status(status)
            .entity(StatutoryAcceptanceResponse.from(result.operation, mapper, actorPartyId))
            .header("X-Idempotency-Replayed", result is StatutoryOperationCreateOutcome.Replayed)
            .build()
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.statutory.accept.read", resource = "#id")
    suspend fun get(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryAcceptanceResponse {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        return StatutoryAcceptanceResponse.from(
            proposals.get(id, company, customerPartyId, actorPartyId),
            mapper,
            actorPartyId,
        )
    }

    @GET
    @Path("/{id}/approval-intent")
    @Authorize(action = "delegation.statutory.accept.intent", resource = "#id")
    suspend fun approvalIntent(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryApprovalIntentResponse {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return StatutoryApprovalIntentResponse(
            id,
            "DELEGATION_STATUTORY_ACCEPTANCE",
            decisions.approvalIntent(id, company, actor),
        )
    }

    @POST
    @Path("/{id}/decisions")
    @Authorize(action = "delegation.statutory.accept.decide", resource = "#id")
    suspend fun decide(
        @PathParam("id") id: UUID,
        request: StatutoryDecisionRequest?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutoryDecisionResponse {
        requireEdge()
        val body = requireNotNull(request) { "decision body is required" }
        val verdict = requireNotNull(body.verdict) { "verdict is required" }
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return StatutoryDecisionResponse.from(decisions.decide(id, company, actor, verdict, body.scaSessionId))
    }

    @GET
    @Path("/{id}/decisions")
    @Authorize(action = "delegation.statutory.accept.read", resource = "#id")
    suspend fun decisionSummaries(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): List<StatutoryDecisionSummaryResponse> {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        return proposals.decisions(id, company, customerPartyId, actorPartyId)
            .map { StatutoryDecisionSummaryResponse(it.actorPartyId, it.verdict, it.decidedAt) }
    }

    @GET
    @Path("/{id}/progress")
    @Authorize(action = "delegation.statutory.accept.read", resource = "#id")
    suspend fun signingProgress(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): StatutorySigningProgress {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return progress.get(id, company, actor)
    }

    @POST
    @Path("/{id}/execute")
    @Authorize(action = "delegation.statutory.accept.execute", resource = "#id")
    suspend fun execute(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam(DelegationResource.CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): DelegationResponse {
        requireEdge()
        val company = requireNotNull(customerPartyId) { "customer profile is required" }
        val actor = requireNotNull(actorPartyId) { "human actor is required" }
        return DelegationResponse.from(execution.execute(id, company, actor))
    }

    private fun requireEdge() {
        if (identity.principal.name != "service-account-openbank-edge") {
            throw ForbiddenException("statutory acceptance requires the authenticated customer edge")
        }
    }
}
