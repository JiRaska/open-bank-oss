// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.rest

import com.openbank.dispute.application.port.`in`.*
import com.openbank.dispute.domain.model.*
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Path("/api/v1/disputes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Disputes", description = "Dispute and chargeback management")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE")
class DisputeResource(
    private val openUseCase: OpenDisputeUseCase,
    private val updateUseCase: UpdateDisputeUseCase,
    private val getUseCase: GetDisputeUseCase
) {
    @POST @Operation(summary = "Open a new dispute")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE")
    fun open(request: OpenDisputeRequest): Uni<Response> =
        openUseCase.open(request).map { Response.status(201).entity(it).build() }
            .onFailure().recoverWithItem { e -> Response.serverError().entity(mapOf("error" to e.message)).build() }

    @GET @Operation(summary = "List disputes by status")
    fun list(@QueryParam("status") status: String?): Uni<List<Dispute>> =
        if (status != null) getUseCase.listByStatus(DisputeStatus.valueOf(status))
        else getUseCase.listByStatus(DisputeStatus.OPEN)

    @GET @Path("/{id}") @Operation(summary = "Get dispute by ID")
    fun get(@PathParam("id") id: UUID): Uni<Response> =
        getUseCase.getDispute(id).map { it?.let { d -> Response.ok(d).build() } ?: Response.status(404).build() }

    @GET @Path("/reference/{ref}") @Operation(summary = "Get dispute by reference")
    fun getByRef(@PathParam("ref") ref: String): Uni<Response> =
        getUseCase.getByReference(ref).map { it?.let { d -> Response.ok(d).build() } ?: Response.status(404).build() }

    @GET @Path("/account/{accountId}") @Operation(summary = "List disputes for an account")
    fun listByAccount(@PathParam("accountId") accountId: UUID): Uni<List<Dispute>> =
        getUseCase.listByAccount(accountId)

    @PUT @Path("/{id}") @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE") @Authorize(action = "dispute.update", resource = "#id") @Operation(summary = "Update dispute status/resolution")
    fun update(@PathParam("id") id: UUID, request: UpdateDisputeRequest): Uni<Response> =
        updateUseCase.update(id, request).map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.serverError().entity(mapOf("error" to e.message)).build() }

    @POST @Path("/{id}/evidence") @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE") @Operation(summary = "Add evidence to a dispute")
    fun addEvidence(@PathParam("id") id: UUID, evidence: DisputeEvidence): Uni<Response> =
        updateUseCase.addEvidence(id, evidence).map { Response.status(201).entity(it).build() }

    @POST @Path("/{id}/withdraw") @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE") @Operation(summary = "Withdraw a dispute")
    fun withdraw(@PathParam("id") id: UUID, @QueryParam("actor") actor: String): Uni<Response> =
        updateUseCase.withdraw(id, actor).map { Response.ok(it).build() }

    @POST @Path("/{id}/escalate") @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE") @Operation(summary = "Escalate a dispute")
    fun escalate(@PathParam("id") id: UUID, @QueryParam("actor") actor: String): Uni<Response> =
        updateUseCase.escalate(id, actor).map { Response.ok(it).build() }

    @GET @Path("/{id}/timeline") @Operation(summary = "Get dispute timeline")
    fun getTimeline(@PathParam("id") id: UUID): Uni<List<DisputeTimelineEvent>> =
        getUseCase.getTimeline(id)

    @GET @Path("/{id}/evidence") @Operation(summary = "Get dispute evidence")
    fun getEvidence(@PathParam("id") id: UUID): Uni<List<DisputeEvidence>> =
        getUseCase.getEvidence(id)
}
