// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.CheckDelegationCommand
import com.openbank.delegation.application.port.`in`.CheckDelegationUseCase
import com.openbank.delegation.application.port.`in`.GetDelegationUseCase
import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.OfferDelegationUseCase
import com.openbank.delegation.application.port.`in`.RespondDelegationUseCase
import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.`in`.RevokeDelegationUseCase
import com.openbank.delegation.application.port.`in`.SuspendDelegationCommand
import com.openbank.delegation.infrastructure.rest.dto.CheckDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.DelegationCheckResponse
import com.openbank.delegation.infrastructure.rest.dto.DelegationResponse
import com.openbank.delegation.infrastructure.rest.dto.OfferDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.RevokeDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.SuspendDelegationRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Delegations", description = "Customer-to-party delegated access lifecycle (ADR-0232)")
@Path("/api/v1/delegations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class DelegationResource(
    private val offerDelegation: OfferDelegationUseCase,
    private val respondDelegation: RespondDelegationUseCase,
    private val revokeDelegation: RevokeDelegationUseCase,
    private val getDelegation: GetDelegationUseCase,
    private val checkDelegation: CheckDelegationUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    @Operation(summary = "Offer a delegation grant (OFFERED); idempotent via X-Request-ID")
    @POST
    @Authorize(action = "delegation.offer", resource = "#request.grantorPartyId")
    suspend fun offer(
        request: OfferDelegationRequest?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context uriInfo: UriInfo,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val idempotencyKey = xRequestId?.takeIf { it.isNotBlank() }

        idempotencyKey?.let { key ->
            idempotencyStore.get(offerKey(request.grantorPartyId, key))?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val grant = offerDelegation.offer(
            OfferDelegationCommand(
                grantorPartyId = request.grantorPartyId,
                granteePartyId = request.granteePartyId,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                capabilities = request.capabilities,
                approvalPolicy = request.approvalPolicy,
                requiredApprovals = request.requiredApprovals,
                perTransactionLimit = request.perTransactionLimit?.toDomain(),
                dailyLimit = request.dailyLimit?.toDomain(),
                monthlyLimit = request.monthlyLimit?.toDomain(),
                exposure = request.exposure?.toDomain(),
                validTo = request.validTo,
                grantScaSessionId = request.grantScaSessionId,
                note = request.note,
            ),
        )
        val responseBody = DelegationResponse.from(grant)
        idempotencyKey?.let { key ->
            idempotencyStore.save(
                offerKey(request.grantorPartyId, key),
                201,
                objectMapper.writeValueAsString(responseBody),
            )
        }

        return Response.created(uriInfo.absolutePathBuilder.path(grant.id.toString()).build())
            .entity(responseBody).build()
    }

    @Operation(summary = "Get delegation grant by ID")
    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.read", resource = "#id")
    suspend fun getById(@PathParam("id") id: UUID): DelegationResponse =
        DelegationResponse.from(getDelegation.getDelegation(id))

    @Operation(summary = "List grants offered BY a party (Shared by me)")
    @GET
    @Path("/grantor/{partyId}")
    @Authorize(action = "delegation.list", resource = "#partyId")
    suspend fun listByGrantor(@PathParam("partyId") partyId: UUID): List<DelegationResponse> =
        getDelegation.listByGrantor(partyId).map { DelegationResponse.from(it) }

    @Operation(summary = "List grants offered TO a party (Shared with me)")
    @GET
    @Path("/grantee/{partyId}")
    @Authorize(action = "delegation.list", resource = "#partyId")
    suspend fun listByGrantee(@PathParam("partyId") partyId: UUID): List<DelegationResponse> =
        getDelegation.listByGrantee(partyId).map { DelegationResponse.from(it) }

    @Operation(summary = "Accept an OFFERED grant after the grantee's SCA challenge completes")
    @POST
    @Path("/{id}/accept")
    @Authorize(action = "delegation.accept", resource = "#id")
    suspend fun accept(
        @PathParam("id") id: UUID,
        @QueryParam("granteePartyId") granteePartyId: UUID,
        @QueryParam("scaSessionId") scaSessionId: UUID,
    ): DelegationResponse = DelegationResponse.from(respondDelegation.accept(id, granteePartyId, scaSessionId))

    @Operation(summary = "Decline an OFFERED grant (grantee)")
    @POST
    @Path("/{id}/decline")
    @Authorize(action = "delegation.decline", resource = "#id")
    suspend fun decline(
        @PathParam("id") id: UUID,
        @QueryParam("granteePartyId") granteePartyId: UUID,
    ): DelegationResponse = DelegationResponse.from(respondDelegation.decline(id, granteePartyId))

    @Operation(summary = "Renounce an ACTIVE grant (grantee gives the access back)")
    @POST
    @Path("/{id}/renounce")
    @Authorize(action = "delegation.renounce", resource = "#id")
    suspend fun renounce(
        @PathParam("id") id: UUID,
        @QueryParam("granteePartyId") granteePartyId: UUID,
    ): DelegationResponse = DelegationResponse.from(respondDelegation.renounce(id, granteePartyId))

    @Operation(summary = "Revoke a grant (grantor or bank); transitions to REVOKED and enqueues DelegationRevoked")
    @DELETE
    @Path("/{id}")
    @Authorize(action = "delegation.revoke", resource = "#id")
    suspend fun revoke(
        @PathParam("id") id: UUID,
        @QueryParam("revokedBy") revokedBy: UUID,
        request: RevokeDelegationRequest?,
    ): DelegationResponse {
        requireNotNull(request) { "request body is required" }
        return DelegationResponse.from(revokeDelegation.revoke(RevokeDelegationCommand(id, revokedBy, request.reason)))
    }

    @Operation(summary = "Suspend an ACTIVE grant (bank / fraud-AML signal)")
    @POST
    @Path("/{id}/suspend")
    @Authorize(action = "delegation.suspend", resource = "#id")
    suspend fun suspend(@PathParam("id") id: UUID, request: SuspendDelegationRequest?): DelegationResponse {
        requireNotNull(request) { "request body is required" }
        return DelegationResponse.from(revokeDelegation.suspend(SuspendDelegationCommand(id, request.reason)))
    }

    @Operation(summary = "Reinstate a SUSPENDED grant")
    @POST
    @Path("/{id}/reinstate")
    @Authorize(action = "delegation.reinstate", resource = "#id")
    suspend fun reinstate(@PathParam("id") id: UUID): DelegationResponse =
        DelegationResponse.from(revokeDelegation.reinstate(id))

    @Operation(
        summary = "Whether an active grant covers a capability on a resource (services without a local projection)",
    )
    @POST
    @Path("/check")
    @Authorize(action = "delegation.check")
    suspend fun check(request: CheckDelegationRequest?): DelegationCheckResponse {
        requireNotNull(request) { "request body is required" }
        return DelegationCheckResponse.from(
            checkDelegation.check(
                CheckDelegationCommand(
                    granteePartyId = request.granteePartyId,
                    resourceType = request.resourceType,
                    resourceId = request.resourceId,
                    capability = request.capability,
                    amount = request.amount?.toDomain(),
                ),
            ),
        )
    }

    private fun offerKey(grantorPartyId: UUID, requestId: String) = "delegation:offer:$grantorPartyId:$requestId"
}
