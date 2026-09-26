// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

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
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * The checker's queue (issue #5679, mirroring transaction's/swift's shape). Without it a
     * parked `opsmessage.compose` decision is invisible: the maker gets a 202 with an approval
     * id and no way to hand it over except out of band, and the Redis TTL (24h) then expires the
     * request silently. Read-only, and deliberately NOT filtered to "approvals someone else
     * made" — the self-approval guard lives in ApprovalStore.decide; seeing the queue is not
     * authority over it.
     *
     * `opsmessage.approval.read` needs no new `rules.yaml` grant: it ends in `.read`, so
     * `rest.rego`'s `operator-read-any` rule (any `ROLE_OPERATOR`/`ROLE_ADMIN` HUMAN principal,
     * any action ending `.list`/`.read`) already admits it — the same mechanism
     * `transaction.approval.read` and `swift.approval.read` rely on.
     */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for an operator message")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, ApprovalEndpointSupport.checkerId(identity))
}
