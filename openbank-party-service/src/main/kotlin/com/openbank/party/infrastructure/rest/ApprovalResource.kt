// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

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
 *
 * The shared body (`limit` clamping, null-body 400, unknown-id 404, checker id resolution,
 * self-approval propagation, wire DTOs) lives in `ApprovalEndpointSupport` (libs-runtime,
 * issue #10915); only the annotations here are per-service.
 */
@Path("/api/v1/parties/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Party Approvals", description = "Four-eyes decisions for gated party actions")
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * The checker's queue (issue #5679). Without this read, a parked `party.merge` decision is
     * visible only to whoever received its approval id out of band, and silently expires after the
     * shared store's 24-hour TTL. The queue is intentionally readable by the maker too: seeing a
     * request is not authority to decide it, and [ApprovalStore.decide] still enforces four eyes.
     */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for a gated party action")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, ApprovalEndpointSupport.checkerId(identity))
}
