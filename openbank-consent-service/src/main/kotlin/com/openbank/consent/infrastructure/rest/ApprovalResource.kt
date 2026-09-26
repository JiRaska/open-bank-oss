// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest

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
 * Checker-facing endpoint for the four-eyes gate on `consent.grant` (creating a new consent)
 * and `consent.revoke` (operator-initiated revocation) (ADR-0155). A maker's call is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id when
 * OPA flags the action `four_eyes_required`; a DIFFERENT operator decides it here, then the
 * maker retries the original call with an `X-Approval-Id` header. One decide endpoint covers
 * every gated action on this service, same as `openbank-billing-service`'s `ApprovalResource`
 * covering both `billing.post`/`billing.reverse`. `consent.activate` is deliberately NOT gated
 * here — see the `four_eyes.verbs` guardrail note in `rules.yaml` (issue #938 follow-up).
 *
 * The shared body (`limit` clamping, null-body 400, unknown-id 404, checker id resolution,
 * self-approval propagation, wire DTOs) lives in `ApprovalEndpointSupport` (libs-runtime,
 * issue #10915); only the annotations here are per-service.
 */
@Path("/api/v1/consents/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Consent Approvals", description = "Four-eyes decisions for gated consent actions")
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    /** Read-only checker queue; deciding remains on PATCH and preserves four-eyes separation. */
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "consent.approval.read", resource = "")
    @Operation(summary = "List pending consent approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "consent.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for a gated consent action")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, identity)
}
