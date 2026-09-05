package com.openbank.referral.infrastructure.rest

import com.openbank.referral.application.ReferralService
import com.openbank.referral.domain.ReferralValidationException
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateReferralProgramRequest(
    val name: String,
    val version: Int,
    val rewardAmount: BigDecimal,
    val currency: String,
    val qualifyingEvent: String,
    val attributionWindowEndsAt: Instant? = null,
)
data class IssueReferralInviteRequest(val referrerPartyId: UUID)
data class AttributeReferralInviteRequest(val refereePartyId: UUID)
data class QualifyReferralInviteRequest(val eventName: String, val eventId: String)

@Path("/api/v1/referrals")
@ApplicationScoped
@RolesAllowed("ROLE_OPERATOR")
class ReferralResource(private val service: ReferralService, private val identity: SecurityIdentity) {
    private fun actor() = identity.principal.name

    @POST
    @Path("/programs")
    suspend fun create(req: CreateReferralProgramRequest): Response = Response.status(
        Response.Status.CREATED,
    ).entity(
        service.createProgram(
            req.name,
            req.version,
            req.rewardAmount,
            req.currency,
            req.qualifyingEvent,
            req.attributionWindowEndsAt,
            actor(),
        ),
    ).build()

    @POST
    @Path("/programs/{id}/publish")
    suspend fun publish(@PathParam("id") id: UUID): Response = Response.ok(service.publishProgram(id, actor())).build()

    @POST
    @Path("/programs/{id}/invites")
    suspend fun invite(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        req: IssueReferralInviteRequest,
    ): Response {
        if (key.isNullOrBlank()) throw ReferralValidationException("Idempotency-Key is required")
        return Response.status(
            Response.Status.CREATED,
        ).entity(service.issueInvite(id, req.referrerPartyId, key, actor())).build()
    }

    @POST
    @Path("/invites/{token}/attribute")
    suspend fun attribute(
        @PathParam("token") token: String,
        @HeaderParam("Idempotency-Key") key: String?,
        req: AttributeReferralInviteRequest,
    ): Response {
        if (key.isNullOrBlank()) throw ReferralValidationException("Idempotency-Key is required")
        return Response.ok(service.attributeInvite(token, req.refereePartyId, key, actor())).build()
    }

    @POST
    @Path("/invites/{token}/qualify")
    suspend fun qualify(
        @PathParam("token") token: String,
        @HeaderParam("Idempotency-Key") key: String?,
        req: QualifyReferralInviteRequest,
    ): Response {
        if (key.isNullOrBlank()) throw ReferralValidationException("Idempotency-Key is required")
        return Response.status(
            Response.Status.ACCEPTED,
        ).entity(service.qualifyInvite(token, req.eventName, req.eventId, key, actor())).build()
    }
}
