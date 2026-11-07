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

/**
 * DEPRECATED alias of [RevisionApprovalResource] kept for the compatibility window (#13429): the
 * admin-ui Product Studio and OPA policy still call `/pension-approvals` and the
 * `catalog.pensionApproval.*` actions. Same handler, same identity rules; remove together with
 * its `non_kernel_files` entry in rules.yaml, which expires so this cannot become permanent.
 */
@Deprecated("use RevisionApprovalResource (/approvals)")
@ApplicationScoped
@Path("/api/v2/offerings/{offeringId}/revisions/{revisionId}/pension-approvals")
@Produces(MediaType.APPLICATION_JSON)
class PensionRevisionApprovalResource(private val handler: RevisionApprovalHandler) {
    @GET
    @Authorize(
        action = "catalog.pensionApproval.read",
        resource = "#offeringId",
        attributes = ["azp", "subject", "preferred_username"],
    )
    @RolesAllowed(CatalogRoles.READ)
    suspend fun list(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
    ): List<RevisionApprovalResponse> = handler.list(offeringId, revisionId)

    @POST
    @Authorize(action = "catalog.pensionApproval.decide", resource = "#offeringId")
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
