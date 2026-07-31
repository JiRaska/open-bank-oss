// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Checker-facing endpoint for the four-eyes gate (ADR-0155). A maker's
 * `POST /applications/{id}/disburse` call on a `four_eyes_required` disbursement is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id; a
 * DIFFERENT operator decides it here, then the maker retries the original call with an
 * `X-Approval-Id` header.
 */
@Path("/api/v1/lending/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending Approvals", description = "Four-eyes decisions for gated lending actions")
class ApprovalResource(private val approvalStore: ApprovalStore) {

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed("ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Authorize(action = "lending.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals (ADR-0227 inbox federation)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response {
        val pending = approvalStore.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT))
        return Response.ok(pending.map { it.toResponse() }).build()
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_LENDING_OFFICER", "ROLE_ADMIN")
    @Authorize(action = "lending.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest): Response {
        val decided = approvalStore.decide(id, checkerId(), request.approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return Response.ok(decided.toResponse()).build()
    }

    // .principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id
    // (sc.userPrincipal?.name), or approval.makerId and this checker's id would be
    // formatted differently for the same real person and the self-approval guard
    // in ApprovalStore.decide could silently fail to catch a maker approving their
    // own request. SecurityIdentity (not @Context SecurityContext) because this is
    // a `suspend fun` — see AccountResource.operatorId() for the same workaround.
    private fun checkerId(): String = identity.principal?.name ?: "anonymous"

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
    val makerId: String?,
    val createdAt: String?,
    val decidedBy: String?,
)

fun PendingApproval.toResponse() = ApprovalResponse(
    id = id,
    action = action,
    resourceId = resourceId,
    status = status.name,
    makerId = makerId,
    createdAt = createdAt.toString(),
    decidedBy = decidedBy,
)
