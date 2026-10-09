// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.standingorder.application.port.`in`.CreateStandingOrderCommand
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.rest.dto.CreateStandingOrderRequest
import com.openbank.standingorder.infrastructure.rest.dto.StandingOrderReceiptLookupRequest
import com.openbank.standingorder.infrastructure.rest.dto.toResponse
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.ForbiddenException
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
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/api/v1/standing-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StandingOrderResource(
    private val useCase: StandingOrderUseCase,
    private val identity: SecurityIdentity,
    private val objectMapper: ObjectMapper,
) {

    /** The authenticated issuer and subject are bound separately from customer forwarding headers. */
    private val actorScope: String
        get() {
            val jwt = identity.principal as? JsonWebToken
            val issuer = jwt?.getClaim<Any>("iss")?.toString()?.trim()?.ifBlank { null }
            val subject = jwt?.subject?.trim()?.ifBlank { null }
                ?: identity.principal.name.trim().ifBlank { error("Authenticated principal has no stable scope") }
            return listOfNotNull(issuer, subject).joinToString("\u001f")
        }

    private fun trustedCustomerProvenance(
        partyHeader: String?,
        actorHeader: String?,
        allowLegacyEdgeCreate: Boolean = false,
    ): Pair<UUID?, UUID?> {
        val jwt = identity.principal as? JsonWebToken
        val edge = jwt?.getClaim<String>("preferred_username") == "service-account-openbank-edge" &&
            jwt.getClaim<String>("azp") == "openbank-edge"
        if (partyHeader == null && actorHeader == null && !edge) return null to null
        if (
            !edge ||
            partyHeader.isNullOrBlank() ||
            '\u001f' !in actorScope ||
            (actorHeader.isNullOrBlank() && !(allowLegacyEdgeCreate && actorHeader == null))
        ) {
            throw ForbiddenException("Customer provenance requires the authenticated customer edge")
        }
        val party = runCatching { UUID.fromString(partyHeader) }.getOrNull()
        val actor = actorHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (party == null || (actorHeader != null && actor == null)) {
            throw ForbiddenException("Customer provenance is invalid")
        }
        return party to actor
    }

    @POST
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun create(
        req: CreateStandingOrderRequest,
        @HeaderParam("X-Customer-Party-Id") customerPartyHeader: String?,
        @HeaderParam("X-Customer-Actor-Id") customerActorHeader: String?,
    ): Response {
        require(req.idempotencyKey.isNotBlank() && req.idempotencyKey.length <= 255) {
            "idempotencyKey is required and must be at most 255 characters"
        }
        // During an edge-first/rail-first rollout, an older edge sends only the party header.
        // Creation remains available, but that row cannot yield a customer receipt later.
        val (partyId, actorId) = trustedCustomerProvenance(
            customerPartyHeader,
            customerActorHeader,
            allowLegacyEdgeCreate = true,
        )
        if (partyId != null && partyId != req.partyId) {
            throw ForbiddenException("Customer party does not match standing order")
        }
        val principal = actorScope
        val requestHash = RequestFingerprints.of(
            objectMapper,
            "POST",
            "/api/v1/standing-orders",
            mapOf(
                "request" to req,
                "principal" to principal,
                "partyId" to partyId,
                "actorId" to actorId,
            ),
        )
        val order = useCase.create(
            CreateStandingOrderCommand(
                req.idempotencyKey, req.partyId, req.debitAccountId,
                req.debtorIban, req.debtorName,
                req.creditorIban, req.creditorName, req.creditorBic,
                req.amountMinorUnits, req.currency, req.frequency, req.paymentType,
                req.remittanceInfo, req.startDate, req.endDate,
                replacesStandingOrderId = req.replacesStandingOrderId,
                requestHash = requestHash,
                initiatingPrincipal = principal,
                initiatingPartyId = partyId,
                initiatingActorId = actorId,
            ),
        )
        return Response.status(201).entity(order.toResponse()).build()
    }

    @POST
    @Path("/receipts/lookup")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun lookupReceipt(
        request: StandingOrderReceiptLookupRequest?,
        @HeaderParam("X-Customer-Party-Id") customerPartyHeader: String?,
        @HeaderParam("X-Customer-Actor-Id") customerActorHeader: String?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        require(request.idempotencyKey.isNotBlank() && request.idempotencyKey.length <= 255) {
            "idempotencyKey is required and must be at most 255 characters"
        }
        val (partyId, actorId) = trustedCustomerProvenance(customerPartyHeader, customerActorHeader)
        if (partyId == null || actorId == null) {
            throw ForbiddenException("Customer receipt lookup requires the authenticated customer edge")
        }
        return Response.ok(
            useCase.findReceipt(request.idempotencyKey, request.debitAccountId, actorScope, partyId, actorId),
        ).build()
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
