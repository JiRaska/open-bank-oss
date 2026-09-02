// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.rest

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
 * Checker-facing endpoint for the four-eyes gate on `billing.post` (ADR-0143 step 4 / ADR-0155).
 * A maker's `POST /fees/post` call is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id when OPA
 * flags the action `four_eyes_required`; a DIFFERENT operator decides it here, then the maker
 * retries the original call with an `X-Approval-Id` header. Mirrors
 * `openbank-sepa-payment`'s `ApprovalResource` exactly.
 */
@Path("/api/v1/fees/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Billing Approvals", description = "Four-eyes decisions for gated fee-posting actions")
class ApprovalResource(private val approvalStore: ApprovalStore) {

    @Inject
    lateinit var identity: SecurityIdentity

    /** Read-only checker queue; deciding remains on PATCH and preserves four-eyes separation. */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "billing.approval.read", resource = "")
    @Operation(summary = "List pending billing approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response {
        val pending = approvalStore.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT))
        return Response.ok(pending.map { it.toResponse() }).build()
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "billing.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for a fee posting")
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
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id, or approval.makerId and
    // this checker's id would be formatted differently for the same real person and the
    // self-approval guard in ApprovalStore.decide could silently fail to catch a maker approving
    // their own request. SecurityIdentity (not @Context SecurityContext) since this is a
    // `suspend fun` — see AccountResource.operatorId() for the same workaround.
    private fun checkerId(): String = identity.principal?.name ?: "anonymous"

    private companion object {
        /** ApprovalStore scans Redis; keep the caller-controlled result bounded. */
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
