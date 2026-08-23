// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

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
 * Checker-facing endpoint for the four-eyes gate on `party.merge` — retiring a duplicate
 * identity into a survivor (ADR-0179), the one destructive-of-identity action this service
 * exposes. A maker's `POST /api/v1/parties/{id}/merge` is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id
 * when OPA flags the action `four_eyes_required` (rules.yaml `four_eyes.actions`); a
 * DIFFERENT operator decides it here, then the maker retries the original call with an
 * `X-Approval-Id` header. Mirrors `openbank-consent-service`'s and
 * `openbank-notification-service`'s `ApprovalResource` — one decide endpoint covers every
 * gated action on the service.
 *
 * Note the checker's roles: ROLE_OPERATOR / ROLE_ADMIN, the SAME pair the merge endpoint
 * itself admits. Segregation of duties here is by IDENTITY, not by role — enforced in
 * [ApprovalStore.decide], which throws
 * [com.openbank.libs.approval.SelfApprovalNotAllowedException] when the checker is the
 * maker. That choice is deliberate: a role-based split would need a role the running
 * Keycloak realm actually issues, and issue #2540 measured four template-declared roles
 * missing from the live realm — a split on one of those would be a gate nobody could pass.
 * ROLE_OPERATOR and ROLE_ADMIN are both live.
 */
@Path("/api/v1/parties/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Party Approvals", description = "Four-eyes decisions for gated party actions")
class ApprovalResource(private val approvalStore: ApprovalStore) {

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * The checker's queue (issue #5679, mirroring sanctions, lending, ledger, fx and
     * domestic-payment). Without it a parked `party.merge` decision is invisible: the maker gets
     * a 202 with an approval id and no way to hand it over except out of band, so the four-eyes
     * ceremony only completed if the two operators were already talking. The Redis TTL (24h)
     * then expired the request silently.
     *
     * Read-only, and deliberately NOT filtered to "approvals someone else made": the
     * self-approval guard lives in `RedisApprovalStore.decide`, and refusing at read time would
     * only hide a maker's own request from them while still letting them attempt it. Seeing the
     * queue is not authority over it.
     */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response {
        val pending = approvalStore.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT))
        return Response.ok(pending.map { it.toApprovalResponse() }).build()
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for a gated party action")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response {
        // A JSON `null` body deserialises to null even though the Kotlin type is non-nullable, so
        // the first field access threw NPE and JAX-RS answered 500 on a four-eyes approval endpoint
        // (#3029, found by the first working run of api-fuzz-authenticated). Same guard the other
        // resources in this fleet already use; libs-runtime maps IllegalArgumentException to 400.
        requireNotNull(request) { "request body is required" }
        val decided = approvalStore.decide(id, checkerId(), request.approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return Response.ok(decided.toApprovalResponse()).build()
    }

    // .principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id, or approval.makerId and
    // this checker's id would be formatted differently for the same real person and the
    // self-approval guard in ApprovalStore.decide could silently fail to catch a maker approving
    // their own request. SecurityIdentity (not @Context SecurityContext) since this is a
    // `suspend fun` — mirrors consent-service's ApprovalResource.
    private fun checkerId(): String = identity.principal?.name ?: "anonymous"

    private companion object {
        // Same ceiling as sanctions/lending/ledger/fx/domestic-payment's queue. The read is a
        // Redis scan, and an unbounded `limit` from a query parameter is a trivially reachable
        // amplification.
        const val MAX_PENDING_LIMIT = 200
    }
}

data class DecideApprovalRequest(val approve: Boolean)

data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    // makerId and createdAt were absent while the only endpoint was PATCH-by-id: a checker who
    // already held the id needed neither. A QUEUE does — "who asked" is what a second pair of
    // eyes is checking, and "how old" is the only visible sign of a request about to expire
    // against the 24h Redis TTL.
    val makerId: String?,
    val createdAt: String?,
    val decidedBy: String?,
)

fun PendingApproval.toApprovalResponse() = ApprovalResponse(
    id = id,
    action = action,
    resourceId = resourceId,
    status = status.name,
    makerId = makerId,
    createdAt = createdAt.toString(),
    decidedBy = decidedBy,
)
