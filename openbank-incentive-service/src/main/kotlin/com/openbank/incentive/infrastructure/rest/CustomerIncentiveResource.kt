// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.rest

import com.openbank.incentive.application.IncentiveApplication
import com.openbank.incentive.domain.OfferRef
import com.openbank.incentive.domain.ReservationStatus
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.Optional
import java.util.UUID

data class CustomerReserveCodeRequest(
    val code: String,
    val productRef: String,
    val attributionRef: UUID,
    val partyRef: String? = null,
)

data class CustomerReservationResponse(
    val id: UUID,
    val offerRef: OfferRef,
    val productRef: String,
    val attributionRef: UUID,
    val reservedAt: Instant,
    val expiresAt: Instant,
    val status: ReservationStatus,
)

data class CustomerCommitReservationRequest(
    val productRef: String,
    val qualifiedAt: Instant,
    val partyRef: String? = null,
)

data class CustomerReleaseReservationRequest(val productRef: String, val partyRef: String? = null)

/**
 * Trusted customer-edge boundary. The retail client never supplies party identity or offer terms:
 * customer-edge derives the party from its Keycloak JWT and resolves the offer from the opaque
 * campaign interaction before calling this ROLE_API endpoint.
 */
@Path("/api/v1/customer-incentives")
@ApplicationScoped
@RolesAllowed("ROLE_API")
class CustomerIncentiveResource(
    private val application: IncentiveApplication,
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.incentive.customer-caller-principal")
    private val callerPrincipal: Optional<String>,
) {
    @POST
    @Path("/offers/{id}/reservations")
    suspend fun reserve(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") partyId: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        request: CustomerReserveCodeRequest,
    ): Response {
        if (!callerPermitted()) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        require(request.partyRef == null) { "partyRef must not be supplied by the customer request" }
        val trustedParty = requireTrustedParty(partyId)
        require(!key.isNullOrBlank()) { "Idempotency-Key is required" }
        val reservation = application.reserve(
            id,
            request.code,
            trustedParty.toString(),
            request.productRef,
            request.attributionRef,
            key,
            identity.principal.name,
        )
        return Response.status(Response.Status.CREATED).entity(
            CustomerReservationResponse(
                reservation.id,
                reservation.offerRef,
                reservation.productRef,
                requireNotNull(reservation.attributionRef),
                reservation.reservedAt,
                reservation.expiresAt,
                reservation.status,
            ),
        ).build()
    }

    @POST
    @Path("/reservations/{id}/commit")
    suspend fun commit(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") partyId: String?,
        request: CustomerCommitReservationRequest,
    ): Response {
        if (!callerPermitted()) return Response.status(Response.Status.FORBIDDEN).build()
        require(request.partyRef == null) { "partyRef must not be supplied by the customer request" }
        val trustedParty = requireTrustedParty(partyId)
        val reservation = application.commitAttributed(
            id,
            trustedParty.toString(),
            request.productRef,
            identity.principal.name,
            request.qualifiedAt,
        )
        return Response.ok(reservation.toCustomerResponse()).build()
    }

    @POST
    @Path("/reservations/{id}/release")
    suspend fun release(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Customer-Party-Id") partyId: String?,
        request: CustomerReleaseReservationRequest,
    ): Response {
        if (!callerPermitted()) return Response.status(Response.Status.FORBIDDEN).build()
        require(request.partyRef == null) { "partyRef must not be supplied by the customer request" }
        val trustedParty = requireTrustedParty(partyId)
        val reservation = application.releaseAttributed(
            id,
            trustedParty.toString(),
            request.productRef,
            identity.principal.name,
        )
        return Response.ok(reservation.toCustomerResponse()).build()
    }

    private fun callerPermitted(): Boolean {
        val permittedCaller = callerPrincipal.orElse("")
        return permittedCaller.isNotBlank() && identity.principal?.name == permittedCaller
    }

    private fun requireTrustedParty(partyId: String?): UUID {
        val trustedParty = requireNotNull(partyId?.let(::parseUuid)) { "X-Customer-Party-Id is required" }
        require(trustedParty != ZERO_UUID) { "X-Customer-Party-Id must not be the nil UUID" }
        return trustedParty
    }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        val ZERO_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}

private fun com.openbank.incentive.domain.PromoReservation.toCustomerResponse() = CustomerReservationResponse(
    id,
    offerRef,
    productRef,
    requireNotNull(attributionRef),
    reservedAt,
    expiresAt,
    status,
)
