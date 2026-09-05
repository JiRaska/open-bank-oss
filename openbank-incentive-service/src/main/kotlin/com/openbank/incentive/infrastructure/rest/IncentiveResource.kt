// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.rest

import com.openbank.incentive.application.CreateOffer
import com.openbank.incentive.application.IncentiveApplication
import com.openbank.incentive.domain.OfferRef
import com.openbank.incentive.domain.PromoReservation
import com.openbank.incentive.domain.ReservationStatus
import com.openbank.incentive.domain.StackingPolicy
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant
import java.util.UUID

data class CreateOfferRequest(
    val name: String,
    val version: Int,
    val productScope: Set<String>,
    val effectiveFrom: Instant,
    val expiresAt: Instant,
    val totalLimit: Int,
    val perPartyLimit: Int,
    val stackingPolicy: StackingPolicy,
)

data class ImportCodesRequest(
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS
     * of a collection, so `{"codes": [null]}` deserialises happily into a `List<String>` holding a
     * null. Writing the type honestly is what makes [requireCodes] reachable instead of dead code.
     */
    val codes: List<String?> = emptyList(),
) {
    /**
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`;
     * no service-local mapper is added (#526).
     */
    fun requireCodes(): List<String> = codes.mapIndexed { index, code ->
        requireNotNull(code) { "codes[$index] must not be null" }
    }
}
data class ReserveCodeRequest(val code: String, val partyRef: String, val productRef: String)
data class ReservationResponse(
    val id: UUID,
    val offerRef: OfferRef,
    val partyRef: String,
    val productRef: String,
    val idempotencyKey: String,
    val reservedAt: Instant,
    val expiresAt: Instant,
    val status: ReservationStatus,
)

@Path("/api/v1/incentives")
@ApplicationScoped
@RolesAllowed("ROLE_OPERATOR")
@Suppress("TooManyFunctions") // Each method is a separately governed HTTP lifecycle operation.
class IncentiveResource(private val application: IncentiveApplication, private val identity: JsonWebToken) {
    private fun actor() = identity.name

    @GET
    @Path("/offers")
    suspend fun listPublished(): Response = Response.ok(mapOf("items" to application.listPublishedOffers())).build()

    @GET
    @Path("/offers/{id}")
    suspend fun getOffer(@PathParam("id") id: UUID): Response = application.findOffer(id)
        ?.let { Response.ok(it).build() }
        ?: Response.status(Response.Status.NOT_FOUND).build()

    @POST
    @Path("/offers")
    suspend fun create(request: CreateOfferRequest): Response = Response.status(Response.Status.CREATED).entity(
        application.createOffer(
            CreateOffer(
                request.name,
                request.version,
                request.productScope,
                request.effectiveFrom,
                request.expiresAt,
                request.totalLimit,
                request.perPartyLimit,
                request.stackingPolicy,
            ),
            actor(),
        ),
    ).build()

    @POST
    @Path("/offers/{id}/submit")
    suspend fun submit(@PathParam("id") id: UUID): Response = Response.ok(application.submit(id, actor())).build()

    @POST
    @Path("/offers/{id}/publish")
    suspend fun publish(@PathParam("id") id: UUID): Response = Response.ok(application.publish(id, actor())).build()

    @POST
    @Path("/offers/{id}/codes")
    suspend fun importCodes(@PathParam("id") id: UUID, request: ImportCodesRequest): Response = Response.status(
        Response.Status.CREATED,
    ).entity(mapOf("imported" to application.addCodes(id, request.requireCodes(), actor()))).build()

    @POST
    @Path("/offers/{id}/reservations")
    suspend fun reserve(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReserveCodeRequest,
    ): Response {
        require(!key.isNullOrBlank()) { "Idempotency-Key is required" }
        return Response.status(Response.Status.CREATED)
            .entity(
                application.reserve(id, request.code, request.partyRef, request.productRef, null, key, actor())
                    .toResponse(),
            ).build()
    }

    @POST
    @Path("/reservations/{id}/commit")
    suspend fun commit(@PathParam("id") id: UUID): Response =
        Response.ok(application.commit(id, actor()).toResponse()).build()

    @POST
    @Path("/reservations/{id}/release")
    suspend fun release(@PathParam("id") id: UUID): Response =
        Response.ok(application.release(id, actor()).toResponse()).build()

    @POST
    @Path("/maintenance/expire")
    suspend fun expire(): Response = Response.ok(mapOf("expired" to application.expireDue())).build()
}

private fun PromoReservation.toResponse() = ReservationResponse(
    id,
    offerRef,
    partyRef,
    productRef,
    idempotencyKey,
    reservedAt,
    expiresAt,
    status,
)
