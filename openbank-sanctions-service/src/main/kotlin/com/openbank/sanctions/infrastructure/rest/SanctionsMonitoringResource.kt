// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sanctions.application.port.`in`.MonitoredSubjectRequest
import com.openbank.sanctions.application.port.`in`.SanctionsMonitoringUseCase
import com.openbank.sanctions.infrastructure.security.MonitoringCallerIdentity
import io.quarkus.security.identity.SecurityIdentity
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
 * Authorized with the `sanctions.create` action of `POST /api/v3/sanctions/screen`, because the
 * callers are the same services: one that may screen a subject may ask for it to be kept under
 * screening, and may withdraw that.
 *
 * The owner of a registration is the calling service itself, read from its token by
 * [MonitoringCallerIdentity]; no request value names it. A caller therefore reads, replaces and
 * deletes only what it registered, and the same `subjectRef` under two callers is two subjects.
 *
 * There is deliberately no read operation: a finding is never returned over REST. It leaves this
 * service only as an event to the owning service, so no customer-reachable surface can reveal
 * that a subject matched.
 */
@Path("/api/v3/sanctions/monitored-subjects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SanctionsMonitoringResource(
    private val useCase: SanctionsMonitoringUseCase,
    private val identity: SecurityIdentity,
) {

    @PUT
    @Path("/{subjectRef}")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "sanctions.create", resource = "")
    suspend fun register(@PathParam("subjectRef") subjectRef: String, request: MonitoredSubjectRequest): Response {
        val registration = useCase.register(MonitoringCallerIdentity.ownerOf(identity), subjectRef, request)
        val status = if (registration.created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status).entity(registration).build()
    }

    @DELETE
    @Path("/{subjectRef}")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "sanctions.create", resource = "")
    suspend fun deregister(@PathParam("subjectRef") subjectRef: String): Response {
        // 204 whether or not a row existed: deregistration is idempotent.
        useCase.deregister(MonitoringCallerIdentity.ownerOf(identity), subjectRef)
        return Response.noContent().build()
    }
}
