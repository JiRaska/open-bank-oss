// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.infrastructure.rest

import com.openbank.securityscanner.application.IctIncidentService
import com.openbank.securityscanner.application.ReportIncidentCommand
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Path("/api/v1/ict-incidents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "ICT Incidents")
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class IctIncidentResource(private val service: IctIncidentService, private val clock: Clock) {

    @POST
    suspend fun reportIncident(req: ReportIncidentRequest): Response {
        val incident = service.reportIncident(
            ReportIncidentCommand(
                title = req.title,
                description = req.description,
                category = IncidentCategory.valueOf(req.category),
                severity = IncidentSeverity.valueOf(req.severity),
                affectedServices = req.affectedServices,
                detectedAt = req.detectedAt ?: Instant.now(clock),
                assignedTo = req.assignedTo,
            ),
        )
        return Response.status(201).entity(incident).build()
    }

    @GET
    suspend fun listIncidents(
        @QueryParam("status") status: String?,
        @QueryParam("severity") severity: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response = Response.ok(
        service.listIncidents(
            status = status?.let { IncidentStatus.valueOf(it) },
            severity = severity?.let { IncidentSeverity.valueOf(it) },
            limit = limit,
            offset = offset,
        ),
    ).build()

    @GET
    @Path("/{id}")
    suspend fun getIncident(@PathParam("id") id: UUID): Response = Response.ok(service.getIncident(id)).build()

    @PATCH
    @Path("/{id}/status")
    suspend fun updateStatus(@PathParam("id") id: UUID, req: UpdateStatusRequest): Response {
        val updated = service.updateStatus(
            id = id,
            status = IncidentStatus.valueOf(req.status),
            containedAt = req.containedAt,
            resolvedAt = req.resolvedAt,
            rtoMinutes = req.rtoMinutes,
            rpoMinutes = req.rpoMinutes,
        )
        return Response.ok(updated).build()
    }

    @POST
    @Path("/{id}/regulatory-report")
    suspend fun markReportedToRegulator(@PathParam("id") id: UUID, req: RegulatoryReportRequest): Response =
        Response.ok(service.markReportedToRegulator(id, req.regulatoryReportId)).build()
}

data class ReportIncidentRequest(
    val title: String,
    val description: String,
    val category: String,
    val severity: String,
    val affectedServices: List<String>,
    val detectedAt: Instant?,
    val assignedTo: String?,
)

data class UpdateStatusRequest(
    val status: String,
    val containedAt: Instant?,
    val resolvedAt: Instant?,
    val rtoMinutes: Int?,
    val rpoMinutes: Int?,
)

data class RegulatoryReportRequest(val regulatoryReportId: String)
