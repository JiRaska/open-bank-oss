// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.usecase.StatutoryDelegationProposalService
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
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
) {
    companion object {
        fun from(operation: StatutoryDelegationOperation, mapper: ObjectMapper) = StatutoryProposalResponse(
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
        )
    }
}

/** Customer-edge-only JOINT proposal surface. PENDING is evidence, never a usable delegation. */
@Path("/api/v1/delegations/statutory-operations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class StatutoryDelegationResource(
    private val service: StatutoryDelegationProposalService,
    private val mapper: ObjectMapper,
    private val identity: SecurityIdentity,
) {
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
        val response = StatutoryProposalResponse.from(result.operation, mapper)
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
        return StatutoryProposalResponse.from(service.get(id, principal, customerPartyId, actorPartyId), mapper)
    }

    private fun requireEdge() {
        if (identity.principal.name != "service-account-openbank-edge") {
            throw ForbiddenException("statutory proposals require the authenticated customer edge")
        }
    }
}
