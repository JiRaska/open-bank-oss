// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.web.ApprovalEndpointSupport
import com.openbank.libs.approval.web.DecideApprovalRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
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
 * `POST /batches/{id}/settle` call on a `four_eyes_required` batch is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a
 * `PendingApproval` id; a DIFFERENT operator decides it here, then the maker
 * retries the original call with an `X-Approval-Id` header.
 *
 * The shared body (`limit` clamping, null-body 400, unknown-id 404, checker id resolution,
 * self-approval propagation, wire DTOs) lives in `ApprovalEndpointSupport` (libs-runtime,
 * issue #10915); only the annotations here are per-service.
 */
@Path("/api/v1/clearing/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Clearing Approvals", description = "Four-eyes decisions for gated clearing actions")
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * The checker's queue (issue #5679, mirroring sanctions #3472, lending, ledger and balance).
     * Without it a parked decision on `clearingBatch.settle`/`clearingBatch.triggerCycle` is
     * invisible: the maker gets a 202 with an approval id and no way to hand it over except out
     * of band, so the four-eyes ceremony only completes if the two operators are already talking,
     * and the Redis TTL (24h) then expires the request silently. Read-only, and deliberately NOT
     * filtered to "approvals someone else made": the self-approval guard lives in
     * `RedisApprovalStore.decide`, and refusing at read time would only hide a maker's own
     * request from them while still letting them attempt it.
     *
     * Same role set as `decide` below (ROLE_PAYMENTS/ROLE_ADMIN) — the resource's existing RBAC
     * already treats ROLE_PAYMENTS as an equal alternative to operator/admin on every clearing
     * endpoint, and `clearing_rest_ext.rego`'s `operator-clearing-write` reason is a prefix match
     * on `clearingBatch.*` for exactly {ROLE_OPERATOR, ROLE_ADMIN, ROLE_PAYMENTS} — verified with
     * a real `opa eval` against the regenerated bundle (issue #5679): `clearingBatch.approval.read`
     * resolves `allow=true` for all three roles and `allow=false` for ROLE_VIEWER, no rules.yaml
     * change needed.
     */
    @GET
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, identity)
}
