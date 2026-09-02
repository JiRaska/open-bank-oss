// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.rest

import com.openbank.finops.application.port.incoming.GetAnomaliesUseCase
import com.openbank.finops.application.port.incoming.RunFinOpsAnalysisUseCase
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.FinOpsRunReport
import com.openbank.finops.domain.model.RunTrigger
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

@Path("/api/v1/finops")
@Produces(MediaType.APPLICATION_JSON)
class FinOpsResource(
    private val runAnalysis: RunFinOpsAnalysisUseCase,
    private val getAnomalies: GetAnomaliesUseCase,
) {
    @POST
    @Path("/analysis/trigger")
    @RolesAllowed("ROLE_ADMIN")
    fun triggerAnalysis(): FinOpsRunReport = runBlocking {
        runAnalysis.run(RunTrigger.OPERATOR_MANUAL)
    }

    @GET
    @Path("/anomalies")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getActiveAnomalies(): List<CostAnomaly> = getAnomalies.getActive()

    @GET
    @Path("/anomalies/{id}")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getAnomaly(@PathParam("id") id: String): CostAnomaly =
        getAnomalies.getById(id) ?: throw NotFoundException("Anomaly $id not found")
}
