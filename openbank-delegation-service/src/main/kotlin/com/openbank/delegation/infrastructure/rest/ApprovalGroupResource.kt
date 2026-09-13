// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.ApprovalGroupUseCase
import com.openbank.delegation.application.port.`in`.CreateApprovalGroupCommand
import com.openbank.delegation.application.port.`in`.ReviseApprovalGroupCommand
import com.openbank.delegation.application.usecase.ApprovalGroupScaBinding
import com.openbank.delegation.domain.model.ApprovalGroup
import com.openbank.delegation.infrastructure.rest.DelegationResource.Companion.CUSTOMER_ACTOR_PARTY_HEADER
import com.openbank.delegation.infrastructure.rest.DelegationResource.Companion.CUSTOMER_PARTY_HEADER
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Approval groups", description = "Owner-managed approver rosters (ADR-0284 D3)")
@Path("/api/v1/delegations/approval-groups")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class ApprovalGroupResource(private val groups: ApprovalGroupUseCase) {

    @POST
    @Path("/sca-reference")
    @Authorize(action = "delegation.approval-group.manage", resource = "")
    @Operation(summary = "Calculate the canonical reference that the management SCA must sign")
    fun scaReference(
        request: ApprovalGroupScaReferenceRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
    ): ApprovalGroupScaReferenceResponse {
        val body = requireNotNull(request) { "request body is required" }
        val owner = requireNotNull(callerPartyId) { "$CUSTOMER_PARTY_HEADER header is required" }
        val reference = if (body.groupId == null) {
            require(body.expectedRevision == null) { "expectedRevision is only valid with groupId" }
            ApprovalGroupScaBinding.create(owner, body.name, body.validatedMembers(), body.threshold)
        } else {
            ApprovalGroupScaBinding.revise(
                owner,
                body.groupId,
                requireNotNull(body.expectedRevision) { "expectedRevision is required with groupId" },
                body.name,
                body.validatedMembers(),
                body.threshold,
            )
        }
        return ApprovalGroupScaReferenceResponse(reference)
    }

    @POST
    @Authorize(action = "delegation.approval-group.manage", resource = "")
    @Operation(summary = "Create an SCA-bound approval group")
    suspend fun create(
        request: ApprovalGroupWriteRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): Response {
        val body = requireNotNull(request) { "request body is required" }
        val owner = requireNotNull(callerPartyId) { "$CUSTOMER_PARTY_HEADER header is required" }
        val created = groups.create(body.toCreate(owner, actorPartyId ?: owner))
        return Response.status(Response.Status.CREATED).entity(created.toResponse()).build()
    }

    @GET
    @Authorize(action = "delegation.approval-group.read", resource = "")
    @Operation(summary = "List the authenticated owner's approval groups")
    suspend fun list(@HeaderParam(CUSTOMER_PARTY_HEADER) callerPartyId: UUID?): List<ApprovalGroupResponse> {
        val owner = requireNotNull(callerPartyId) { "$CUSTOMER_PARTY_HEADER header is required" }
        return groups.list(owner, owner).map { it.toResponse() }
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.approval-group.read", resource = "#id")
    @Operation(summary = "Get one approval group owned by the authenticated party")
    suspend fun get(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
    ): ApprovalGroupResponse {
        val owner = requireNotNull(callerPartyId) { "$CUSTOMER_PARTY_HEADER header is required" }
        return groups.get(id, owner, owner).toResponse()
    }

    @PUT
    @Path("/{id}")
    @Authorize(action = "delegation.approval-group.manage", resource = "#id")
    @Operation(summary = "Replace an approval-group roster under optimistic revision and SCA")
    suspend fun revise(
        @PathParam("id") id: UUID,
        request: ApprovalGroupWriteRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): ApprovalGroupResponse {
        requireNotNull(request) { "request body is required" }
        val owner = requireNotNull(callerPartyId) { "$CUSTOMER_PARTY_HEADER header is required" }
        return groups.revise(request.toRevise(id, owner, actorPartyId ?: owner)).toResponse()
    }

    @DELETE
    @Path("/{id}")
    @Authorize(action = "delegation.approval-group.manage", resource = "#id")
    @Operation(summary = "Deactivate an approval group for future operation snapshots")
    suspend fun deactivate(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
        @HeaderParam(CUSTOMER_ACTOR_PARTY_HEADER) actorPartyId: UUID?,
    ): ApprovalGroupResponse {
        val owner = requireNotNull(callerPartyId) { "$CUSTOMER_PARTY_HEADER header is required" }
        return groups.deactivate(id, owner, owner, actorPartyId ?: owner).toResponse()
    }
}

data class ApprovalGroupScaReferenceRequest(
    val groupId: UUID? = null,
    val expectedRevision: Long? = null,
    val name: String,
    val members: Set<UUID?>,
    val threshold: Int,
)

data class ApprovalGroupScaReferenceResponse(val reference: String)

data class ApprovalGroupWriteRequest(
    val name: String,
    val members: Set<UUID?>,
    val threshold: Int,
    val scaSessionId: UUID,
    val expectedRevision: Long? = null,
) {
    fun toCreate(owner: UUID, actor: UUID) = CreateApprovalGroupCommand(
        ownerPartyId = owner,
        callerPartyId = owner,
        actorPartyId = actor,
        name = name,
        members = validatedMembers(),
        threshold = threshold,
        scaSessionId = scaSessionId,
    )

    fun toRevise(id: UUID, owner: UUID, actor: UUID) = ReviseApprovalGroupCommand(
        id = id,
        ownerPartyId = owner,
        callerPartyId = owner,
        actorPartyId = actor,
        expectedRevision = requireNotNull(expectedRevision) { "expectedRevision is required for replacement" },
        name = name,
        members = validatedMembers(),
        threshold = threshold,
        scaSessionId = scaSessionId,
    )
}

private fun ApprovalGroupScaReferenceRequest.validatedMembers(): Set<UUID> = members.validated()
private fun ApprovalGroupWriteRequest.validatedMembers(): Set<UUID> = members.validated()
private fun Set<UUID?>.validated(): Set<UUID> = mapIndexed { index, member ->
    requireNotNull(member) { "members[$index] is required" }
}.toSet()

data class ApprovalGroupResponse(
    val id: UUID,
    val ownerPartyId: UUID,
    val name: String,
    val members: Set<UUID>,
    val threshold: Int,
    val revision: Long,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

private fun ApprovalGroup.toResponse() = ApprovalGroupResponse(
    id = id,
    ownerPartyId = ownerPartyId,
    name = name,
    members = members,
    threshold = threshold,
    revision = revision,
    active = active,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
