// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.infrastructure.rest

import com.openbank.standingorder.application.port.`in`.*
import com.openbank.standingorder.infrastructure.rest.dto.*
import com.openbank.libs.authz.Authorize
import jakarta.ws.rs.*; import jakarta.ws.rs.core.MediaType; import jakarta.ws.rs.core.Response
import java.time.LocalDate; import java.util.UUID

@Path("/api/v1/standing-orders")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
class StandingOrderResource(private val useCase: StandingOrderUseCase) {

    @POST
    suspend fun create(req: CreateStandingOrderRequest): Response {
        val order = useCase.create(CreateStandingOrderCommand(
            req.idempotencyKey, req.partyId, req.debitAccountId,
            req.creditorIban, req.creditorName, req.creditorBic,
            req.amountMinorUnits, req.currency, req.frequency, req.paymentType,
            req.remittanceInfo, req.startDate, req.endDate
        ))
        return Response.status(201).entity(order.toResponse()).build()
    }

    @GET
    suspend fun listAll() = useCase.listAll().map { it.toResponse() }

    @GET @Path("/{id}")
    suspend fun get(@PathParam("id") id: UUID) =
        useCase.getById(id) ?: throw NotFoundException()

    @GET @Path("/party/{partyId}")
    suspend fun listByParty(@PathParam("partyId") partyId: UUID) =
        useCase.listByParty(partyId).map { it.toResponse() }

    @POST @Path("/{id}/pause")
    @Authorize(action = "standingOrder.pause", resource = "#id")
    suspend fun pause(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") actor: String?) =
        useCase.pause(id, actor(actor)).toResponse()

    @POST @Path("/{id}/resume")
    suspend fun resume(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") actor: String?) =
        useCase.resume(id, actor(actor)).toResponse()

    @DELETE @Path("/{id}")
    suspend fun cancel(@PathParam("id") id: UUID, @HeaderParam("X-Customer-Party-Id") actor: String?): Response {
        useCase.cancel(id, actor(actor))
        return Response.noContent().build()
    }

    // Attribute the lifecycle action to the customer the edge forwarded (X-Customer-Party-Id),
    // not a blanket "system" — so the order's own history records who paused/cancelled it.
    private fun actor(partyId: String?): String = partyId?.takeIf { it.isNotBlank() } ?: "system"
}
