// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.infrastructure.rest

import com.openbank.devops.application.port.incoming.GetFindingsUseCase
import com.openbank.devops.application.port.incoming.RunDevOpsAnalysisUseCase
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.RunTrigger
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
class DevOpsResource(private val runAnalysis: RunDevOpsAnalysisUseCase, private val getFindings: GetFindingsUseCase) {
    @POST
    @Path("/analysis/trigger")
    @RolesAllowed("platform-admin")
    fun triggerAnalysis(): DevOpsRunReport = runBlocking {
        runAnalysis.run(RunTrigger.OPERATOR_MANUAL)
    }

    @GET
    @Path("/findings")
    @RolesAllowed("platform-admin", "platform-viewer")
    fun getActiveFindings(): List<DevOpsFinding> = runBlocking {
        getFindings.getActive()
    }

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("platform-admin", "platform-viewer")
    fun getFinding(@PathParam("id") id: String): DevOpsFinding = runBlocking {
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")
    }
}
