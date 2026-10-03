// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.web.ApprovalEndpointSupport
import com.openbank.libs.approval.web.DecideApprovalRequest
import com.openbank.libs.approval.web.toApprovalResponse
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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

/**
 * Checker-facing endpoint for the four-eyes gate on SCA operator actions (`device.revoke` today,
 * any SCA action OPA flags `four_eyes_required`). [com.openbank.libs.authz.AuthorizeInterceptor]
 * parks the maker's request with HTTP 202 and an approval id bound to the exact request
 * (endpoint + arguments, #11675); a DIFFERENT operator decides it here, then the maker retries the
 * identical request with `X-Approval-Id`. The checker id is taken from [SecurityIdentity], never
 * from the body, and self-approval is refused by [ApprovalStore.decide] — both in
 * [ApprovalEndpointSupport].
 */
@Path("/api/v1/sca/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ApprovalResource(private val approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "scaChallenge.approval.read", resource = "")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    /** One approval in any status, so a maker/checker can see whether it was decided or consumed. */
    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "scaChallenge.approval.read", resource = "#id")
    suspend fun get(@PathParam("id") id: String): Response {
        val approval = approvalStore.find(id) ?: throw NotFoundException("no approval with id=$id")
        return Response.ok(approval.toApprovalResponse()).build()
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "scaChallenge.approval.decide", resource = "#id")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        support.decide(id, request) { identity }
}
