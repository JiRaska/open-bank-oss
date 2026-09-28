// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.web.ApprovalEndpointSupport
import com.openbank.libs.approval.web.DecideApprovalRequest
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
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
 * `PATCH /status` call on a `four_eyes_required` payment is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a
 * `PendingApproval` id; a DIFFERENT operator decides it here, then the maker
 * retries the original call with an `X-Approval-Id` header.
 *
 * The shared body (`limit` clamping, null-body 400, unknown-id 404, checker id resolution,
 * self-approval propagation, wire DTOs) lives in `ApprovalEndpointSupport` (libs-runtime,
 * issue #10915); only the annotations here are per-service. Note the roles are intentionally
 * NOT the same for both operations: `listPending` stays `ROLE_OPERATOR`/`ROLE_ADMIN` only, while
 * `decide` additionally admits `ROLE_PAYMENTS` — unchanged from before this migration.
 */
@Path("/api/v1/sepa-payments/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "SEPA Payment Approvals", description = "Four-eyes decisions for gated SEPA payment actions")
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * The checker's queue (issue #5679, mirroring sanctions #3472 and lending). Without it a
     * parked decision on a `four_eyes_required` SEPA payment is invisible: the maker gets a 202
     * with an approval id and no way to hand it over except out of band, so the ceremony only
     * completes if the two operators are already talking, and the Redis TTL (24h) then expires
     * the request silently. Read-only, and deliberately NOT filtered to "approvals someone else
     * made": the self-approval guard lives in `RedisApprovalStore.decide`, and refusing at read
     * time would only hide a maker's own request from them while still letting them attempt it.
     */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "sepaPayment.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, identity)
}
