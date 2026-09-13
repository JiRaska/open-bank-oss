// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.ReserveSpendCommand
import com.openbank.delegation.application.port.`in`.ReserveSpendUseCase
import com.openbank.delegation.infrastructure.rest.dto.ReserveSpendRequest
import com.openbank.delegation.infrastructure.rest.dto.SpendReservationResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * ADR-0249 D3 — reserve before the money moves, confirm on a settled outcome, release on failure.
 *
 * A separate resource class from [DelegationResource] but the same `/api/v1/delegations` root: the
 * reservation lifecycle is its own aggregate with its own failure modes, and [DelegationResource]
 * is already at detekt's `TooManyFunctions` ceiling.
 *
 * Idempotency is the CALLER's key in the body, not `X-Request-ID` as on the offer route. The edge
 * already holds a stable key for the payment it is about to initiate, and it is that key — not a
 * per-HTTP-attempt one — that must not double-count when a rail replays.
 */
@Tag(name = "Delegations", description = "Cumulative delegated-spend reservations (ADR-0249 D3)")
@Path("/api/v1/delegations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class SpendReservationResource(private val reserveSpend: ReserveSpendUseCase) {

    @Operation(summary = "Reserve headroom under the grant's per-transaction, daily and monthly ceilings")
    @POST
    @Path("/{id}/reservations")
    @Authorize(action = "delegation.reserve", resource = "#id")
    suspend fun reserve(
        @PathParam("id") id: UUID,
        request: ReserveSpendRequest?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @Context uriInfo: UriInfo,
    ): Response {
        requireNotNull(request) { "request body is required" }
        require(request.idempotencyKey.isNotBlank()) { "idempotencyKey is required" }
        val result = reserveSpend.reserve(
            ReserveSpendCommand(
                callerPartyId = customerPartyId,
                delegationId = id,
                amount = request.toMoney(),
                idempotencyKey = request.idempotencyKey,
                operationType = request.operationType,
            ),
        )
        val reservationId = result.reservation.id
        // A replay answers 201 with the SAME reservationId rather than 409: the caller asked for a
        // reservation under a key and it has one. The header is how a client tells the two apart.
        return Response.created(uriInfo.absolutePathBuilder.path(reservationId.toString()).build())
            .entity(SpendReservationResponse.from(result.reservation))
            .header("X-Idempotency-Replayed", result.replayed.toString())
            .build()
    }

    @Operation(summary = "Confirm a reservation — the money moved, the headroom stays consumed")
    @POST
    @Path("/{id}/reservations/{rid}/confirm")
    @Authorize(action = "delegation.reserve.confirm", resource = "#id")
    suspend fun confirm(
        @PathParam("id") id: UUID,
        @PathParam("rid") reservationId: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): SpendReservationResponse =
        SpendReservationResponse.from(reserveSpend.confirm(id, reservationId, customerPartyId))

    @Operation(summary = "Release a reservation — the payment did not happen, the headroom comes back")
    @POST
    @Path("/{id}/reservations/{rid}/release")
    @Authorize(action = "delegation.reserve.release", resource = "#id")
    suspend fun release(
        @PathParam("id") id: UUID,
        @PathParam("rid") reservationId: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): SpendReservationResponse =
        SpendReservationResponse.from(reserveSpend.release(id, reservationId, customerPartyId))
}
