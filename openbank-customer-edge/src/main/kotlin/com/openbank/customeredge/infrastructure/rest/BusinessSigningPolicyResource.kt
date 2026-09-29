// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.BusinessSigningHandlers.Companion.ACTING_FOR
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Business signing (#10281); behaviour in [BusinessSigningHandlers]. */
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
