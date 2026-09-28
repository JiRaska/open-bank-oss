// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest

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
 * `POST /api/v1/swift` (`swift.send`) call is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a
 * `PendingApproval` id; a DIFFERENT operator decides it here, then the maker
 * retries the original call with an `X-Approval-Id` header.
 *
 * `swift.send` has no `@PathParam` (it is not resource-scoped — there's no
 * message id to gate on until after it's created), so the resulting
 * [PendingApproval.resourceId] is always `null`; the approval binds on
 * action + maker only, not action + resource + maker. The `{id}` path param
 * here is the approval's own id, unrelated to the gated action's resource.
 *
 * The shared body (`limit` clamping, null-body 400, unknown-id 404, checker id resolution,
 * self-approval propagation, wire DTOs) lives in `ApprovalEndpointSupport` (libs-runtime,
 * issue #10915); only the annotations here are per-service.
 */
@Path("/api/v1/swift/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "SWIFT Approvals", description = "Four-eyes decisions for gated SWIFT actions")
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * The checker's queue (issue #5679, mirroring sanctions #3472, lending, ledger and
     * domestic-payment). Without it a parked decision is invisible: the maker gets a 202 with
     * an approval id and no way to hand it over except out of band, so the four-eyes ceremony
     * on `swift.send` only completed if the two operators were already talking. The Redis TTL
     * (24h) then expired the request silently.
     *
     * Read-only, and deliberately NOT filtered to "approvals someone else made": the
     * self-approval guard lives in `RedisApprovalStore.decide`, and refusing at read time would
     * only hide a maker's own request from them while still letting them attempt it. Seeing the
     * queue is not authority over it.
     */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "swift.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    // Intentionally protected even though SwiftResource.send() currently has NO
    // @RolesAllowed anywhere in that class (separate, tracked finding — not fixed
    // here). This NEW endpoint uses the standard checker role set so the four-eyes
    // decide path doesn't ship unprotected just because the gated action's own
    // endpoint is.
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "swift.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, identity)
}
