// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.usecase.CloseReadinessService
import com.openbank.ledger.domain.model.CloseReadiness
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/** Evidence only: this endpoint never changes modes or authorizes a deployment. */
@Path("/api/v1/ledger/close-readiness")
@Produces(MediaType.APPLICATION_JSON)
class CloseReadinessResource(private val service: CloseReadinessService) {
    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read")
    suspend fun get(@QueryParam("month") month: String?): CloseReadiness = service.get(month)
}
