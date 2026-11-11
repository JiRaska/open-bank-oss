// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.usecase.StatutoryDelegationActor
import com.openbank.delegation.application.usecase.StatutoryDelegationService
import com.openbank.delegation.application.usecase.StatutoryDelegationTerms
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.infrastructure.rest.DelegationResource.Companion.CUSTOMER_ACTOR_PARTY_HEADER
import com.openbank.delegation.infrastructure.rest.DelegationResource.Companion.CUSTOMER_PARTY_HEADER
import com.openbank.delegation.infrastructure.rest.dto.PreviewDelegationRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
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
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

data class StatutoryIssueRequest(val draft: PreviewDelegationRequest?, val note: String?)
data class StatutorySignatureRequest(val scaChallengeId: UUID?)
data class StatutoryClosureRequest(val cancel: Boolean?)

@Path("/api/v1/delegations/statutory-operations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class StatutoryDelegationResource(
    private val service: StatutoryDelegationService,
    private val mapper: ObjectMapper,
    private val identity: SecurityIdentity,
) {

    @POST
    @Path("/issue")
    @Authorize(action = "delegation.statutory.issue")
    suspend fun issue(
        body: StatutoryIssueRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) principal: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) human: UUID?,
        @HeaderParam("Idempotency-Key") requestKey: String?,
    ): Response {
        val actor = actor(principal, human)
        val draft = requireNotNull(body?.draft) { "draft is required" }
        val terms = StatutoryDelegationTerms(
            grantorPartyId = draft.grantorPartyId,
            granteePartyId = draft.granteePartyId,
            resourceType = draft.resourceType,
            resourceId = draft.resourceId,
            capabilities = draft.capabilities,
            approvalPolicy = draft.approvalPolicy,
            requiredApprovals = draft.requiredApprovals,
            approvalGroupId = draft.approvalGroupId,
            perTransactionLimit = draft.perTransactionLimit?.toDomain(),
            dailyLimit = draft.dailyLimit?.toDomain(),
            monthlyLimit = draft.monthlyLimit?.toDomain(),
            exposure = draft.exposure?.toDomain(),
            recertificationAudience = draft.recertificationAudience,
            validTo = draft.validTo,
            note = body.note,
        )
        val operation = service.startIssue(actor, terms, requestKey(requestKey))
        return respond(view(actor, operation), Response.Status.ACCEPTED)
    }

    @POST
    @Path("/accept/{delegationId}")
    @Authorize(action = "delegation.statutory.accept", resource = "#delegationId")
    suspend fun accept(
        @PathParam("delegationId") delegationId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) principal: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) human: UUID?,
        @HeaderParam("Idempotency-Key") requestKey: String?,
    ): Response {
        val actor = actor(principal, human)
        return respond(
            view(actor, service.startAccept(actor, delegationId, requestKey(requestKey))),
            Response.Status.ACCEPTED,
        )
    }

    @GET
    @Authorize(action = "delegation.statutory.read")
    suspend fun pending(
        @HeaderParam(CUSTOMER_PARTY_HEADER) principal: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) human: UUID?,
    ): Response {
        val actor = actor(principal, human)
        return respond(mapOf("items" to service.pending(actor).map { view(actor, it) }))
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.statutory.read", resource = "#id")
    suspend fun get(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) principal: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) human: UUID?,
    ): Response {
        val actor = actor(principal, human)
        return respond(view(actor, service.get(actor, id)))
    }

    @POST
    @Path("/{id}/sign")
    @Authorize(action = "delegation.statutory.sign", resource = "#id")
    suspend fun sign(
        @PathParam("id") id: UUID,
        body: StatutorySignatureRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) principal: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) human: UUID?,
    ): Response {
        val actor = actor(principal, human)
        val challenge = requireNotNull(body?.scaChallengeId) { "scaChallengeId is required" }
        return respond(view(actor, service.sign(actor, id, challenge)))
    }

    @POST
    @Path("/{id}/close")
    @Authorize(action = "delegation.statutory.close", resource = "#id")
    suspend fun close(
        @PathParam("id") id: UUID,
        body: StatutoryClosureRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) principal: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) human: UUID?,
    ): Response {
        val actor = actor(principal, human)
        val cancel = requireNotNull(body?.cancel) { "cancel is required" }
        return respond(view(actor, service.close(actor, id, cancel)))
    }

    private fun actor(principal: UUID?, human: UUID?): StatutoryDelegationActor {
        if (!ServiceAccountIdentity.isPrincipal(identity, "service-account-openbank-edge")) {
            throw ForbiddenException("only customer-edge may supply statutory company and actor context")
        }
        if (principal == null || human == null || principal == human) {
            throw ForbiddenException("verified company and human actor context is required")
        }
        return StatutoryDelegationActor(principal, human)
    }

    private fun requestKey(value: String?): String {
        require(!value.isNullOrBlank() && value.length <= MAX_REQUEST_KEY) { "Idempotency-Key is required" }
        return value
    }

    private fun view(actor: StatutoryDelegationActor, operation: StatutoryDelegationOperation): Map<String, Any> =
        mapOf(
            "id" to operation.id,
            "action" to operation.action.name,
            "delegationId" to operation.delegationId,
            "companyPartyId" to actor.principalPartyId,
            "status" to operation.status.name,
            "requiredSignatures" to operation.rule.requiredSignatures,
            "signedCount" to operation.signatures.size,
            "signedByCaller" to operation.signatures.any { it.partyId == actor.actorPartyId },
            "initiatedByCaller" to (operation.initiatorPartyId == actor.actorPartyId),
            "expiresAt" to operation.expiresAt,
            "terms" to mapper.readTree(operation.frozenPayload),
            "approvalRequestId" to operation.approvalRequestId,
            "payloadSha256" to operation.payloadDigest.hex,
        )

    private fun respond(body: Any, status: Response.Status = Response.Status.OK): Response =
        Response.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").entity(body).build()

    private companion object {
        const val MAX_REQUEST_KEY = 128
    }
}
