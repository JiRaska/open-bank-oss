// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import com.openbank.notification.infrastructure.persistence.repository.OperatorMessageRepository
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Checker-facing endpoints for the opsmessage.compose four-eyes gate (ADR-0155/ADR-0176 D5). A
 * maker's `POST /opsmessages/{id}/submit` call is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id; a
 * DIFFERENT operator decides it here, then the maker retries the original call with an
 * `X-Approval-Id` header. Mirrors ledger-service's ApprovalResource, split into two named
 * endpoints (not one PATCH + an `approve: Boolean` body) because ADR-0176 D4 names
 * `opsmessage.approve` and `opsmessage.reject` as two distinct actions, each with its own rego
 * allow rule.
 */
@Path("/api/v1/opsmessages/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Operator Message Approvals", description = "Four-eyes decisions for opsmessage.compose")
class OperatorMessageApprovalResource(
    private val approvalStore: ApprovalStore,
    private val operatorMessageRepo: OperatorMessageRepository,
) {

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/{id}/approve")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.approve", resource = "#id")
    @Operation(summary = "Approve a pending operator message — lets the maker's retry send it")
    suspend fun approve(@PathParam("id") id: String): Response {
        val decided = approvalStore.decide(id, checkerId(), approve = true)
            ?: throw NotFoundException("no pending approval with id=$id")
        // The row itself is NOT flipped here — it stays PENDING_APPROVAL until the maker's own
        // retry actually dispatches the message and marks it SENT. Approving only clears the
        // gate; it does not send anything by itself.
        return Response.ok(decided.toResponse()).build()
    }

    @POST
    @Path("/{id}/reject")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.reject", resource = "#id")
    @Operation(summary = "Reject a pending operator message — it will never be sent")
    suspend fun reject(@PathParam("id") id: String): Response {
        val decided = approvalStore.decide(id, checkerId(), approve = false)
            ?: throw NotFoundException("no pending approval with id=$id")
        // Explicitly recorded here, not left for the maker's retry: ApprovalStore refuses a
        // retry against a non-APPROVED decision, so the submit handler's method body — the only
        // other place that ever updates this row — never runs for a rejected message. Without
        // this call the row would stay PENDING_APPROVAL forever.
        operatorMessageRepo.markRejected(UUID.fromString(id))
        return Response.ok(decided.toResponse()).build()
    }

    // .principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id, or approval.makerId and
    // this checker's id would be formatted differently for the same real person and the
    // self-approval guard in ApprovalStore.decide could silently fail to catch a maker approving
    // their own request. Same trap ledger-service's ApprovalResource comments on.
    private fun checkerId(): String = identity.principal?.name ?: "anonymous"
}

data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val decidedBy: String?,
)

fun PendingApproval.toResponse() = ApprovalResponse(
    id = id,
    action = action,
    resourceId = resourceId,
    status = status.name,
    decidedBy = decidedBy,
)
