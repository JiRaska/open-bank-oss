// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sanctions.application.port.`in`.MonitoredSubjectRequest
import com.openbank.sanctions.application.port.`in`.SanctionsMonitoringUseCase
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Registration of subjects for ongoing monitoring (ADR-0376).
 *
 * Protected exactly as `POST /api/v3/sanctions/screen` is — the same roles and the same
 * `sanctions.create` action — because the callers are the same services and the only
 * identity-scoped rule a service account has for this service covers that action. A caller that
 * may screen a subject may ask for it to be kept under screening, and may withdraw that.
 *
 * There is deliberately no read operation: a finding is never returned over REST. It leaves this
 * service only as an event to the owning service, so no customer-reachable surface can reveal
 * that a subject matched.
 */
@Path("/api/v3/sanctions/monitored-subjects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SanctionsMonitoringResource(private val useCase: SanctionsMonitoringUseCase) {

    @PUT
    @Path("/{ownerService}/{subjectRef}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.create", resource = "")
    suspend fun register(
        @PathParam("ownerService") ownerService: String,
        @PathParam("subjectRef") subjectRef: String,
        request: MonitoredSubjectRequest,
    ): Response {
        val registration = useCase.register(ownerService, subjectRef, request)
        val status = if (registration.created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status).entity(registration).build()
    }

    @DELETE
    @Path("/{ownerService}/{subjectRef}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.create", resource = "")
    suspend fun deregister(
        @PathParam("ownerService") ownerService: String,
        @PathParam("subjectRef") subjectRef: String,
    ): Response {
        // 204 whether or not a row existed: deregistration is idempotent.
        useCase.deregister(ownerService, subjectRef)
        return Response.noContent().build()
    }
}
