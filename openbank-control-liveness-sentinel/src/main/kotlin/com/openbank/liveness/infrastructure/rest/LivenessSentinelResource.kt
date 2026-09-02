// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.rest

import com.openbank.liveness.application.port.incoming.GetFindingsUseCase
import com.openbank.liveness.application.port.incoming.RunLivenessCheckUseCase
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

@Path("/api/v1/liveness-sentinel")
@Produces(MediaType.APPLICATION_JSON)
class LivenessSentinelResource(
    private val runCheck: RunLivenessCheckUseCase,
    private val getFindings: GetFindingsUseCase,
) {
    @POST
    @Path("/check/trigger")
    @RolesAllowed("ROLE_ADMIN")
    fun triggerCheck(): LivenessRunReport = runBlocking {
        runCheck.run(RunTrigger.OPERATOR_MANUAL)
    }

    @GET
    @Path("/findings")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getActiveFindings(): List<LivenessFinding> = getFindings.getActive()

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getFinding(@PathParam("id") id: String): LivenessFinding =
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")
}
