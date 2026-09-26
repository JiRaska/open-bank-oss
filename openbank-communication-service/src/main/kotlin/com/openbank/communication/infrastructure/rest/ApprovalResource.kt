// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.rest

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
 * Checker-facing endpoint for the four-eyes gate on `commstyle.publish` (ADR-0285 D3). A
 * maker's `POST /api/v1/personas/style-versions/{id}/publish` call is paused by
 * [com.openbank.libs.authz.AuthorizeInterceptor] with HTTP 202 and a `PendingApproval` id; a
 * DIFFERENT `ROLE_COMMS_APPROVER` decides it here, then the maker (or any `ROLE_COMMS_APPROVER`)
 * retries the original call with an `X-Approval-Id` header. This is also the read side of the
 * ADR-0227 unified approval inbox (D2): the admin-ui BFF federates this queue in.
 *
 * Mirrors `openbank-notification-service`'s `ApprovalResource` file-for-file.
 */
@Path("/api/v1/communications/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Communication Approvals", description = "Four-eyes decisions for commstyle.publish")
class ApprovalResource(approvalStore: ApprovalStore) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed("ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    @Authorize(action = "commstyle.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_COMMS_APPROVER", "ROLE_ADMIN")
    @Authorize(action = "commstyle.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval for a style/playbook publish")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        // Null body -> 400, unknown id -> 404, maker == checker -> SelfApprovalNotAllowedException
        // from ApprovalStore.decide: all in ApprovalEndpointSupport (libs-runtime).
        support.decide(id, request, ApprovalEndpointSupport.checkerId(identity))
}
