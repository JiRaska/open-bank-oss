// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
    @RolesAllowed("platform-admin")
    fun triggerAnalysis(): FinOpsRunReport = runBlocking {
        runAnalysis.run(RunTrigger.OPERATOR_MANUAL)
    }

    @GET
    @Path("/anomalies")
    @RolesAllowed("platform-admin", "platform-viewer")
    fun getActiveAnomalies(): List<CostAnomaly> = runBlocking {
        getAnomalies.getActive()
    }

    @GET
    @Path("/anomalies/{id}")
    @RolesAllowed("platform-admin", "platform-viewer")
    fun getAnomaly(@PathParam("id") id: String): CostAnomaly = runBlocking {
        getAnomalies.getById(id) ?: throw NotFoundException("Anomaly $id not found")
    }
}
