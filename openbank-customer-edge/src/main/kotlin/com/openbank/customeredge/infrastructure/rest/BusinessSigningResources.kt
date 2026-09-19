// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.BusinessSigningHandlers.Companion.ACTING_FOR
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

// Business signing routes (#10281). One class per path prefix, each more specific than
// `CustomerBusinessResource`'s `/customer/v1/business`, so JAX-RS class matching reaches them;
// the behaviour lives in [BusinessSigningHandlers].

@Path("/customer/v1/business/signing-policy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class BusinessSigningPolicyResource(private val h: BusinessSigningHandlers) {

    @GET
    @Authorize(action = "customer.business.signing.read", resource = "")
    @Blocking
    fun get(
        @HeaderParam(ACTING_FOR) actingFor: String?,
        @HeaderParam("Accept-Language") acceptLanguage: String?,
    ): Response = h.signingPolicy(actingFor, acceptLanguage)

    @PUT
    @Authorize(action = "customer.business.signing.change", resource = "")
    @Blocking
    fun put(@HeaderParam(ACTING_FOR) actingFor: String?, body: String): Response =
        h.changeSigningPolicy(actingFor, body)
}

@Path("/customer/v1/business/trusted-payees")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class BusinessTrustedPayeesResource(private val h: BusinessSigningHandlers) {

    @GET
    @Authorize(action = "customer.business.signing.read", resource = "")
    @Blocking
    fun list(@HeaderParam(ACTING_FOR) actingFor: String?): Response = h.trustedPayees(actingFor)

    @POST
    @Authorize(action = "customer.business.signing.change", resource = "")
    @Blocking
    fun add(@HeaderParam(ACTING_FOR) actingFor: String?, body: String): Response = h.addTrustedPayee(actingFor, body)

    @DELETE
    @Path("/{id}")
    @Authorize(action = "customer.business.signing.change", resource = "#id")
    @Blocking
    fun remove(@HeaderParam(ACTING_FOR) actingFor: String?, @PathParam("id") id: String): Response =
        h.removeTrustedPayee(actingFor, id)
}

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

@Path("/customer/v1/me/approvals")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class MyApprovalsResource(private val h: BusinessSigningHandlers) {

    @GET
    @Path("/pending")
    @Authorize(action = "customer.business.approvals.read", resource = "")
    @Blocking
    fun pending(): Response = h.myPending()
}
