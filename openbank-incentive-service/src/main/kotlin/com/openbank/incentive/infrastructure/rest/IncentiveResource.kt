// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.rest

import com.openbank.incentive.application.CreateOffer
import com.openbank.incentive.application.IncentiveApplication
import com.openbank.incentive.domain.StackingPolicy
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
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

data class ImportCodesRequest(val codes: List<String>)
data class ReserveCodeRequest(val code: String, val partyRef: String, val productRef: String)

@Path("/api/v1/incentives")
@ApplicationScoped
@RolesAllowed("ROLE_OPERATOR")
class IncentiveResource(private val application: IncentiveApplication, private val identity: SecurityIdentity) {
    private fun actor() = identity.principal.name

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
    ).entity(mapOf("imported" to application.addCodes(id, request.codes))).build()

    @POST
    @Path("/offers/{id}/reservations")
    suspend fun reserve(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReserveCodeRequest,
    ): Response {
        require(!key.isNullOrBlank()) { "Idempotency-Key is required" }
        return Response.status(Response.Status.CREATED)
            .entity(application.reserve(id, request.code, request.partyRef, request.productRef, key)).build()
    }

    @POST
    @Path("/reservations/{id}/commit")
    suspend fun commit(@PathParam("id") id: UUID): Response = Response.ok(application.commit(id, actor())).build()

    @POST
    @Path("/reservations/{id}/release")
    suspend fun release(@PathParam("id") id: UUID): Response = Response.ok(application.release(id, actor())).build()

    @POST
    @Path("/maintenance/expire")
    suspend fun expire(): Response = Response.ok(mapOf("expired" to application.expireDue())).build()
}
