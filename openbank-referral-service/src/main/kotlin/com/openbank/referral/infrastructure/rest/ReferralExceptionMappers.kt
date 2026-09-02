package com.openbank.referral.infrastructure.rest

import com.openbank.referral.domain.ReferralConflictException
import com.openbank.referral.domain.ReferralNotFoundException
import com.openbank.referral.domain.ReferralValidationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

private fun status(s: Response.Status, e: Exception) = Response.status(s).entity(
    mapOf(
        "error" to (e.message ?: s.reasonPhrase),
    ),
).build()

@Provider class ReferralConflictMapper : ExceptionMapper<ReferralConflictException> {
    override fun toResponse(e: ReferralConflictException) = status(Response.Status.CONFLICT, e)
}

@Provider class ReferralNotFoundMapper : ExceptionMapper<ReferralNotFoundException> {
    override fun toResponse(e: ReferralNotFoundException) = status(Response.Status.NOT_FOUND, e)
}

@Provider class ReferralValidationMapper : ExceptionMapper<ReferralValidationException> {
    override fun toResponse(e: ReferralValidationException) = status(Response.Status.BAD_REQUEST, e)
}
