// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Checker-facing endpoint for the four-eyes gate on operator-initiated messaging
 * (ADR-0176 D5). A maker's `POST /api/v1/notifications/messages` call on a
 * `four_eyes_required` action (`opsmessage.compose`) is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id;
 * a DIFFERENT operator decides it here, then the maker retries the original call with an
 * `X-Approval-Id` header.
 *
 * One decide action with an `approve` boolean (mirrors `lending-service`'s
 * `ApprovalResource`), not the separate `opsmessage.approve` / `opsmessage.reject`
 * actions ADR-0176 D4 sketched — that split was schema-level shorthand in the decision
 * record, not a wire-format commitment, and the proven pattern is one endpoint.
 */
@Path("/api/v1/notifications/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Notification Approvals", description = "Four-eyes decisions for opsmessage.compose")
class ApprovalResource(private val approvalStore: ApprovalStore) {

    @Inject
    lateinit var identity: SecurityIdentity

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for an operator message")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response {
        // A JSON `null` body deserialises to null even though the Kotlin type is non-nullable, so
        // the first field access threw NPE and JAX-RS answered 500 on a four-eyes approval endpoint
        // (#3029, found by the first working run of api-fuzz-authenticated). Same guard the other
        // resources in this fleet already use; libs-runtime maps IllegalArgumentException to 400.
        requireNotNull(request) { "request body is required" }
        val decided = approvalStore.decide(id, checkerId(), request.approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return Response.ok(decided.toResponse()).build()
    }

    // .principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id
    // (sc.userPrincipal?.name), or approval.makerId and this checker's id would be
    // formatted differently for the same real person, and ApprovalStore.decide's
    // self-approval guard could silently fail to catch a maker approving their own request.
    private fun checkerId(): String = identity.principal?.name ?: "anonymous"
}

data class DecideApprovalRequest(val approve: Boolean)

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
