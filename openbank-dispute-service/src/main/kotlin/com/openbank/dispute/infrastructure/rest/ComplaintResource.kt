// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.rest

import com.openbank.dispute.application.port.`in`.FileComplaintUseCase
import com.openbank.dispute.application.port.`in`.GetComplaintUseCase
import com.openbank.dispute.application.port.`in`.HandleComplaintUseCase
import com.openbank.dispute.domain.model.CloseComplaintRequest
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.dispute.domain.model.FileComplaintRequest
import com.openbank.dispute.domain.model.InterimReplyRequest
import com.openbank.dispute.domain.model.ResolveComplaintRequest
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Path("/api/v1/complaints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Complaints", description = "Regulatory complaints handling with statutory deadline clock (ADR-0085)")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
class ComplaintResource(
    private val fileUseCase: FileComplaintUseCase,
    private val handleUseCase: HandleComplaintUseCase,
    private val getUseCase: GetComplaintUseCase,
) {
    @POST
    @Operation(summary = "File a new complaint")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    fun file(request: FileComplaintRequest): Uni<Response> =
        fileUseCase.file(request).map { Response.status(HTTP_CREATED).entity(it).build() }

    @GET
    @Authorize(action = "complaint.list")
    @Operation(summary = "List complaints, optionally filtered by status")
    fun list(@QueryParam("status") status: String?): Uni<List<Complaint>> =
        if (status != null) getUseCase.listByStatus(ComplaintStatus.valueOf(status)) else getUseCase.listAll()

    @GET
    @Path("/{id}")
    @Authorize(action = "complaint.read", resource = "#id")
    @Operation(summary = "Get complaint by ID")
    fun get(@PathParam("id") id: UUID): Uni<Response> = getUseCase.getComplaint(id).map {
        it?.let { c -> Response.ok(c).build() }
            ?: Response.status(HTTP_NOT_FOUND).build()
    }

    @POST
    @Path("/{id}/interim-reply")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "complaint.update", resource = "#id")
    @Operation(summary = "Record an interim reply (extends the statutory deadline to 35 business days)")
    fun interimReply(@PathParam("id") id: UUID, request: InterimReplyRequest): Uni<Response> =
        handleUseCase.interimReply(id, request).map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_NOT_FOUND).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/{id}/resolve")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "complaint.update", resource = "#id")
    @Operation(summary = "Resolve a complaint (records an outcome + optional redress flag)")
    fun resolve(@PathParam("id") id: UUID, request: ResolveComplaintRequest): Uni<Response> =
        handleUseCase.resolve(id, request).map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_NOT_FOUND).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/{id}/close")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "complaint.update", resource = "#id")
    @Operation(summary = "Close a complaint (records outcome + root-cause code + optional redress flag)")
    fun close(@PathParam("id") id: UUID, request: CloseComplaintRequest): Uni<Response> =
        handleUseCase.close(id, request).map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_NOT_FOUND).entity(mapOf("error" to e.message)).build()
            }

    companion object {
        private const val HTTP_CREATED = 201
        private const val HTTP_NOT_FOUND = 404
    }
}
