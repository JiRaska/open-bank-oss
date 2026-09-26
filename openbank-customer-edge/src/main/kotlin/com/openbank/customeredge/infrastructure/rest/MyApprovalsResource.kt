// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Business signing (#10281); behaviour in [BusinessSigningHandlers]. */
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
