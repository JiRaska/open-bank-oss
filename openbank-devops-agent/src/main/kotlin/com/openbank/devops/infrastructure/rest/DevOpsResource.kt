// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.rest

import com.openbank.devops.application.port.incoming.DecideFindingUseCase
import com.openbank.devops.application.port.incoming.GetFindingsUseCase
import com.openbank.devops.application.port.incoming.RunDevOpsAnalysisUseCase
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.RunTrigger
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

@Path("/api/v1/devops")
@Produces(MediaType.APPLICATION_JSON)
class DevOpsResource(
    private val runAnalysis: RunDevOpsAnalysisUseCase,
    private val getFindings: GetFindingsUseCase,
    private val decide: DecideFindingUseCase,
) {
    // @Blocking: starts a Temporal workflow and waits for it synchronously — must run on a worker
    // thread, not the event loop. It touches no reactive DB session, so runBlocking is fine here.
    @POST
    @Path("/analysis/trigger")
    @RolesAllowed("ROLE_ADMIN")
    @Blocking
    fun triggerAnalysis(): DevOpsRunReport = runBlocking {
        runAnalysis.run(RunTrigger.OPERATOR_MANUAL)
    }

    // suspend: the reads hit reactive Panache, which needs a Vert.x context — RESTEasy Reactive runs
    // Kotlin suspend resource methods on one, so the session resolves correctly.
    @GET
    @Path("/findings")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getActiveFindings(): List<DevOpsFinding> = getFindings.getActive()

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getFinding(@PathParam("id") id: String): DevOpsFinding =
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")

    // HITL decisions (ADR-0031 D4). platform-admin only — a human operator disposes; the agent
    // (a viewer at most) can never approve its own proposal (segregation of duties).
    @POST
    @Path("/findings/{id}/approve")
    @RolesAllowed("ROLE_ADMIN")
    suspend fun approve(@PathParam("id") id: String): DevOpsFinding =
        decide.approve(id) ?: throw NotFoundException("Finding $id not found")

    @POST
    @Path("/findings/{id}/reject")
    @RolesAllowed("ROLE_ADMIN")
    suspend fun reject(@PathParam("id") id: String): DevOpsFinding =
        decide.reject(id) ?: throw NotFoundException("Finding $id not found")
}
