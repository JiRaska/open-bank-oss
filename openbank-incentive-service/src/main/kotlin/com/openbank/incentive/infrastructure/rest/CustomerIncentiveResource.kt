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
        val permittedCaller = callerPrincipal.orElse("")
        if (permittedCaller.isBlank() || identity.principal?.name != permittedCaller) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        require(request.partyRef == null) { "partyRef must not be supplied by the customer request" }
        val trustedParty = requireNotNull(partyId?.let(::parseUuid)) { "X-Customer-Party-Id is required" }
        require(trustedParty != ZERO_UUID) { "X-Customer-Party-Id must not be the nil UUID" }
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

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        val ZERO_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
