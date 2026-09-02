// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.out.DelegationConcurrentTransitionException
import com.openbank.delegation.application.usecase.DelegationCallerMismatchException
import com.openbank.delegation.application.usecase.DelegationEligibilityException
import com.openbank.delegation.application.usecase.DelegationNotFoundException
import com.openbank.delegation.application.usecase.DelegationNotGranteeException
import com.openbank.delegation.application.usecase.DelegationNotGrantorException
import com.openbank.delegation.application.usecase.DelegationResourceOwnershipException
import com.openbank.delegation.application.usecase.DelegationRolePresetNotFound
import com.openbank.delegation.application.usecase.DelegationScaException
import com.openbank.delegation.application.usecase.DelegationUnsupportedConstraintException
import com.openbank.delegation.application.usecase.SpendReservationNotFoundException
import com.openbank.delegation.application.usecase.SpendReservationRefusedException
import com.openbank.delegation.application.usecase.SpendReservationStateException
import com.openbank.delegation.infrastructure.rest.dto.SpendRefusalResponse
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

private fun errorBody(status: Int, message: String?): Map<String, Any?> = mapOf(
    "status" to status,
    "error" to message,
)

@Provider
class DelegationNotFoundExceptionMapper : ExceptionMapper<DelegationNotFoundException> {
    override fun toResponse(exception: DelegationNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(errorBody(404, exception.message)).build()
}

/** A stale lifecycle command is safe to retry from a fresh representation, never to overwrite. */
@Provider
class DelegationConcurrentTransitionExceptionMapper : ExceptionMapper<DelegationConcurrentTransitionException> {
    override fun toResponse(exception: DelegationConcurrentTransitionException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(errorBody(Response.Status.CONFLICT.statusCode, exception.message))
            .build()
}

@Provider
class DelegationRolePresetNotFoundMapper : ExceptionMapper<DelegationRolePresetNotFound> {
    override fun toResponse(exception: DelegationRolePresetNotFound): Response =
        Response.status(Response.Status.NOT_FOUND)
            .entity(errorBody(Response.Status.NOT_FOUND.statusCode, exception.message))
            .build()
}

@Provider
class DelegationNotGranteeExceptionMapper : ExceptionMapper<DelegationNotGranteeException> {
    override fun toResponse(exception: DelegationNotGranteeException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(errorBody(403, exception.message)).build()
}

@Provider
class DelegationNotGrantorExceptionMapper : ExceptionMapper<DelegationNotGrantorException> {
    override fun toResponse(exception: DelegationNotGrantorException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(errorBody(403, exception.message)).build()
}

@Provider
class DelegationScaExceptionMapper : ExceptionMapper<DelegationScaException> {
    override fun toResponse(exception: DelegationScaException): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(errorBody(400, exception.message)).build()
}

@Provider
class DelegationEligibilityExceptionMapper : ExceptionMapper<DelegationEligibilityException> {
    override fun toResponse(exception: DelegationEligibilityException): Response =
        Response.status(422).entity(errorBody(422, exception.message)).build()
}

@Provider
class DelegationCallerMismatchExceptionMapper : ExceptionMapper<DelegationCallerMismatchException> {
    override fun toResponse(exception: DelegationCallerMismatchException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(errorBody(403, exception.message)).build()
}

/**
 * 400, not 422: the field is not merely unacceptable in this instance, it is not a field this
 * version of the API supports at all — no value of it would be accepted, so there is nothing for
 * the caller to retry with different content. Carries a machine-readable `code` so a client can
 * distinguish "you sent a ceiling we do not enforce" from every other 400 on this route.
 */
@Provider
class DelegationUnsupportedConstraintExceptionMapper : ExceptionMapper<DelegationUnsupportedConstraintException> {
    override fun toResponse(exception: DelegationUnsupportedConstraintException): Response =
        Response.status(Response.Status.BAD_REQUEST)
            .entity(
                errorBody(Response.Status.BAD_REQUEST.statusCode, exception.message) + ("code" to exception.code),
            )
            .build()
}

/**
 * 422, not 403: the request is well-formed and the caller is who they say they are — the
 * *content* of the grant is what cannot be accepted. Same class as the eligibility gate.
 */
@Provider
class DelegationResourceOwnershipExceptionMapper : ExceptionMapper<DelegationResourceOwnershipException> {
    override fun toResponse(exception: DelegationResourceOwnershipException): Response =
        Response.status(422).entity(errorBody(422, exception.message)).build()
}

/**
 * ADR-0249 D3: 409, and the body says which ceiling and how much is left. Not 403 — the caller may
 * well be entitled to spend, just not this much right now — and not 422, because the same request
 * becomes acceptable when the window rolls or a reservation is released.
 */
@Provider
class SpendReservationRefusedExceptionMapper : ExceptionMapper<SpendReservationRefusedException> {
    override fun toResponse(exception: SpendReservationRefusedException): Response {
        val status = Response.Status.CONFLICT
        return Response.status(status).entity(SpendRefusalResponse.from(status.statusCode, exception.decision)).build()
    }
}

@Provider
class SpendReservationNotFoundExceptionMapper : ExceptionMapper<SpendReservationNotFoundException> {
    override fun toResponse(exception: SpendReservationNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND)
            .entity(errorBody(Response.Status.NOT_FOUND.statusCode, exception.message))
            .build()
}

/** Confirming a released reservation, or releasing a confirmed one — the settle cannot be replayed. */
@Provider
class SpendReservationStateExceptionMapper : ExceptionMapper<SpendReservationStateException> {
    override fun toResponse(exception: SpendReservationStateException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(errorBody(Response.Status.CONFLICT.statusCode, exception.message))
            .build()
}
