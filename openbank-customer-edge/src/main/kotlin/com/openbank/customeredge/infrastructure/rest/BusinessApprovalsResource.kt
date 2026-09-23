// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.BusinessSigningHandlers.Companion.ACTING_FOR
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Business signing (#10281); behaviour in [BusinessSigningHandlers]. */
@Path("/customer/v1/business/approvals")
@Produces(MediaType.APPLICATION_JSON)
// No @Consumes: sign carries no body and reject an optional one; a body-less POST must not be a 415.
@RolesAllowed("ROLE_CUSTOMER")
class BusinessApprovalsResource(private val h: BusinessSigningHandlers) {

    @GET
    @Authorize(action = "customer.business.approvals.read", resource = "")
    @Blocking
    fun list(
        @HeaderParam(ACTING_FOR) actingFor: String?,
        @QueryParam("status") status: String?,
        @QueryParam("mine") mine: String?,
    ): Response = h.listApprovals(actingFor, status, mine)

    @GET
    @Path("/{id}")
    @Authorize(action = "customer.business.approvals.read", resource = "#id")
    @Blocking
    fun detail(
        @HeaderParam(ACTING_FOR) actingFor: String?,
        @HeaderParam("Accept-Language") acceptLanguage: String?,
        @PathParam("id") id: String,
    ): Response = h.approval(actingFor, acceptLanguage, id)

    @POST
    @Path("/{id}/sign")
    @Authorize(action = "customer.business.approvals.sign", resource = "#id")
    @Blocking
    fun sign(
        @HeaderParam(ACTING_FOR) actingFor: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
        @HeaderParam("Accept-Language") acceptLanguage: String?,
        @PathParam("id") id: String,
    ): Response = h.sign(actingFor, scaChallengeId, acceptLanguage, id)

    @POST
    @Path("/{id}/reject")
    @Authorize(action = "customer.business.approvals.sign", resource = "#id")
    @Blocking
    fun reject(@HeaderParam(ACTING_FOR) actingFor: String?, @PathParam("id") id: String, body: String?): Response =
        h.reject(actingFor, id, body)
}
