// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.standingorder.application.port.`in`.CreateStandingOrderCommand
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.rest.dto.CreateStandingOrderRequest
import com.openbank.standingorder.infrastructure.rest.dto.toResponse
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/standing-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StandingOrderResource(private val useCase: StandingOrderUseCase) {

    @POST
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun create(req: CreateStandingOrderRequest): Response {
        val order = useCase.create(
            CreateStandingOrderCommand(
                req.idempotencyKey, req.partyId, req.debitAccountId,
                req.debtorIban, req.debtorName,
                req.creditorIban, req.creditorName, req.creditorBic,
                req.amountMinorUnits, req.currency, req.frequency, req.paymentType,
                req.remittanceInfo, req.startDate, req.endDate,
            ),
        )
        return Response.status(201).entity(order.toResponse()).build()
    }

    @GET
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun listAll() = useCase.listAll().map { it.toResponse() }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun get(@PathParam("id") id: UUID) = useCase.getById(id) ?: throw NotFoundException()

    @GET
    @Path("/party/{partyId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun listByParty(@PathParam("partyId") partyId: UUID) = useCase.listByParty(partyId).map { it.toResponse() }

    @POST
    @Path("/{id}/pause")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "standingOrder.pause", resource = "#id")
    suspend fun pause(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") actor: String?) =
        useCase.pause(id, actor(actor)).toResponse()

    @POST
    @Path("/{id}/resume")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun resume(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") actor: String?) =
        useCase.resume(id, actor(actor)).toResponse()

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun cancel(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") actor: String?): Response {
        useCase.cancel(id, actor(actor))
        return Response.noContent().build()
    }

    @PATCH
    @Path("/{id}/record-execution")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun recordExecution(@PathParam("id") id: UUID): Response =
        Response.ok(useCase.confirmExecution(id).toResponse()).build()

    @PATCH
    @Path("/{id}/record-failure")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun recordFailure(@PathParam("id") id: UUID): Response =
        Response.ok(useCase.recordFailure(id).toResponse()).build()

    // Attribute the lifecycle action to the customer the edge forwarded (X-Customer-Party-Id),
    // not a blanket "system" — so the order's own history records who paused/cancelled it.
    private fun actor(partyId: String?): String = partyId?.takeIf { it.isNotBlank() } ?: "system"
}
