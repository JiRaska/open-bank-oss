// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.port.`in`.CardStatusCommand
import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.infrastructure.rest.dto.CardStatusRequest
import com.openbank.cardissuance.infrastructure.rest.dto.IssueCardRequest
import com.openbank.cardissuance.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

@Path("/api/v1/cards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cards", description = "Card issuance and lifecycle management — PCI DSS compliant")
class CardResource(private val cardUseCase: CardUseCase) {

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.create", resource = "")
    @Operation(summary = "Issue a new card")
    suspend fun issueCard(req: IssueCardRequest, @HeaderParam("Idempotency-Key") key: String): Response {
        require(key.isNotBlank()) { "Idempotency-Key header required" }
        val card = cardUseCase.issueCard(req.toCommand(key))
        return Response.created(URI.create("/api/v1/cards/${card.id}")).entity(card.toResponse()).build()
    }

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.list", resource = "")
    @Operation(summary = "List all cards")
    suspend fun listAll(): Response = Response.ok(cardUseCase.listAll().map { it.toResponse() }).build()

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.read", resource = "#id")
    @Operation(summary = "Get card by ID")
    suspend fun getCard(@PathParam("id") id: UUID): Response =
        cardUseCase.getCard(id)?.let { Response.ok(it.toResponse()).build() }
            ?: Response.status(404).entity(mapOf("error" to "Card not found")).build()

    @GET
    @Path("/account/{accountId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.list", resource = "#accountId")
    @Operation(summary = "List cards by account")
    suspend fun listByAccount(@PathParam("accountId") accountId: UUID): Response =
        Response.ok(cardUseCase.listByAccount(accountId).map { it.toResponse() }).build()

    @GET
    @Path("/party/{partyId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.list", resource = "#partyId")
    @Operation(summary = "List cards by party")
    suspend fun listByParty(@PathParam("partyId") partyId: UUID): Response =
        Response.ok(cardUseCase.listByParty(partyId).map { it.toResponse() }).build()

    @POST
    @Path("/{id}/activate")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.activate", resource = "#id")
    @Operation(summary = "Activate a pending card")
    suspend fun activate(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String): Response =
        Response.ok(cardUseCase.activateCard(CardStatusCommand(id, null, operatorId)).toResponse()).build()

    @POST
    @Path("/{id}/block")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "card.block", resource = "#id")
    @Operation(summary = "Block a card")
    suspend fun block(
        @PathParam("id") id: UUID,
        req: CardStatusRequest,
        @HeaderParam("X-Operator-Id") operatorId: String,
    ): Response = Response.ok(cardUseCase.blockCard(CardStatusCommand(id, req.reason, operatorId)).toResponse()).build()

    @POST
    @Path("/{id}/suspend")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.suspend", resource = "#id")
    @Operation(summary = "Suspend a card temporarily")
    suspend fun suspend(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String): Response =
        Response.ok(cardUseCase.suspendCard(CardStatusCommand(id, null, operatorId)).toResponse()).build()

    @POST
    @Path("/{id}/resume")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.resume", resource = "#id")
    @Operation(summary = "Resume a suspended card")
    suspend fun resume(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String): Response =
        Response.ok(cardUseCase.resumeCard(CardStatusCommand(id, null, operatorId)).toResponse()).build()
}
