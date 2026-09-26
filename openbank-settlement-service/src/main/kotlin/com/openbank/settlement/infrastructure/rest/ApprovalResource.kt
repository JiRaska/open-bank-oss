// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.infrastructure.approval.PostgresApprovalStore
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/** A distinct checker decides; the maker retries the bound operation with X-Approval-Id. */
@Path("/api/v1/settlements/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ApprovalResource(
    private val approvals: ApprovalStore,
    private val durableApprovals: PostgresApprovalStore,
    private val identity: SecurityIdentity,
) {
    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.read", resource = "")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): List<ApprovalResponse> =
        approvals.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT)).map { it.toResponse() }

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.read", resource = "#id")
    suspend fun get(@PathParam("id") id: String): ApprovalResponse {
        val approval = approvals.find(id) ?: throw NotFoundException("no approval with id=$id")
        val proposal = durableApprovals.proposalForApproval(id)
        return approval.toResponse().copy(proposalId = proposal?.id?.toString(), instruction = proposal?.instruction())
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.decide", resource = "#id")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): ApprovalResponse {
        requireNotNull(request) { "request body is required" }
        val approve = requireNotNull(request.approve) { "approve is required" }
        if (approve) {
            val instruction = requireNotNull(request.instruction) { "the reviewed settlement instruction is required" }
            val approval = approvals.find(id) ?: throw NotFoundException("no pending approval with id=$id")
            val stored = requireNotNull(durableApprovals.proposalForApproval(id)) {
                "Legacy approval has no reviewable instruction; reject it and resubmit"
            }
            require(approval.action == "settlement.create" && approval.resourceId == instruction.approvalFingerprint) {
                "the reviewed instruction does not match the pending settlement"
            }
            require(stored.instruction().approvalFingerprint == instruction.approvalFingerprint) {
                "the reviewed instruction does not match the stored proposal"
            }
        }
        // Match AuthorizeInterceptor's maker identity, including preferred_username semantics.
        val decided = approvals.decide(id, identity.principal.name, approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return decided.toResponse()
    }

    private companion object {
        const val MAX_PENDING_LIMIT = 200
    }
}

data class DecideApprovalRequest(val approve: Boolean?, val instruction: CreateSettlementRequest? = null)

data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val makerId: String,
    val createdAt: String,
    val decidedBy: String?,
    val proposalId: String? = null,
    val instruction: CreateSettlementRequest? = null,
)

private fun PendingApproval.toResponse() = ApprovalResponse(
    id = id,
    action = action,
    resourceId = resourceId,
    status = status.name,
    makerId = makerId,
    createdAt = createdAt.toString(),
    decidedBy = decidedBy,
)
