// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.ReportingPeriodUseCase
import com.openbank.finrep.application.port.inbound.ReportingPeriods
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@ApplicationScoped
@Path("/api/v1/finrep/periods")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, Roles.OPERATOR)
class ReportingPeriodResource(private val reportingPeriods: ReportingPeriodUseCase) {
    @GET
    suspend fun list(): ReportingPeriods = reportingPeriods.listAvailable()
}
