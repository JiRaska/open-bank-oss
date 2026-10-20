// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import io.quarkus.security.Authenticated
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/** Role-qualified approval of a draft revision, for any schema whose pack declares an approval policy. */
@ApplicationScoped
@Path("/api/v2/offerings/{offeringId}/revisions/{revisionId}/approvals")
@Produces(MediaType.APPLICATION_JSON)
class RevisionApprovalResource(private val handler: RevisionApprovalHandler) {
    @GET
    @Authorize(
        action = "catalog.revisionApproval.read",
        resource = "#offeringId",
        attributes = ["azp", "subject", "preferred_username"],
    )
    @RolesAllowed(CatalogRoles.READ)
    suspend fun list(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
    ): List<RevisionApprovalResponse> = handler.list(offeringId, revisionId)

    /** The required authority depends on the pack policy, so it is checked in [RevisionApprovalHandler]. */
    @POST
    @Authorize(action = "catalog.revisionApproval.decide", resource = "#offeringId")
    @Path("/{role}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authenticated
    suspend fun approve(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
        @PathParam("role") role: String,
        @HeaderParam("If-Match") ifMatch: String?,
        request: RevisionApprovalRequest,
    ): Response = handler.approve(offeringId, revisionId, role, ifMatch, request)
}
