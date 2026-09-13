// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
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
@Path("/api/v1/sca/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ApprovalResource(private val approvals: ApprovalStore, private val identity: SecurityIdentity) {
    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "scaChallenge.approval.read", resource = "")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): List<ApprovalResponse> =
        approvals.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT)).map { it.toResponse() }

    @PATCH
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "scaChallenge.approval.decide", resource = "#id")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): ApprovalResponse {
        requireNotNull(request) { "request body is required" }
        // Match AuthorizeInterceptor's maker identity, including preferred_username semantics.
        val decided = approvals.decide(id, identity.principal.name, request.approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return decided.toResponse()
    }

    private companion object {
        const val MAX_PENDING_LIMIT = 200
    }
}

data class DecideApprovalRequest(val approve: Boolean)

data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val makerId: String,
    val createdAt: String,
    val decidedBy: String?,
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
