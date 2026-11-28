// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.risk.application.port.out.PlanningInputCaptures
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

data class CreatePlanningInputCapture(
    val snapshotId: UUID?,
    val curveSetId: UUID?,
    val treasuryRevisionIds: List<Long?>?,
)

data class PlanningInputCaptureCreated(val id: UUID)

@Path("/api/v1/risk/planning/input-captures")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, "ROLE_RISK")
class PlanningInputCaptureResource(private val captures: PlanningInputCaptures) {
    @POST
    @Authorize(action = "risk.planning.capture.create")
    suspend fun create(request: CreatePlanningInputCapture?): Response {
        val input = requireNotNull(request) { "Planning input capture is required" }
        val snapshot = requireNotNull(input.snapshotId) { "snapshotId is required" }
        val curves = requireNotNull(input.curveSetId) { "curveSetId is required" }
        val revisions = requireNotNull(input.treasuryRevisionIds) { "treasuryRevisionIds is required" }
        require(revisions.size <= MAX_REVISIONS) { "treasuryRevisionIds exceeds $MAX_REVISIONS entries" }
        val ids = revisions.map { requireNotNull(it) { "treasuryRevisionIds cannot contain null" } }
        require(ids.all { it > 0 } && ids.distinct().size == ids.size) {
            "treasuryRevisionIds must contain distinct positive IDs"
        }
        val captureId = captures.capture(snapshot, curves, ids)
        return Response.status(Response.Status.CREATED).entity(PlanningInputCaptureCreated(captureId)).build()
    }

    private companion object {
        const val MAX_REVISIONS = 10000
    }
}
