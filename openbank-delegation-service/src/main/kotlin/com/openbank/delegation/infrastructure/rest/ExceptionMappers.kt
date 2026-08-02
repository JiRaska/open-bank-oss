// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.usecase.DelegationCallerMismatchException
import com.openbank.delegation.application.usecase.DelegationEligibilityException
import com.openbank.delegation.application.usecase.DelegationNotFoundException
import com.openbank.delegation.application.usecase.DelegationNotGranteeException
import com.openbank.delegation.application.usecase.DelegationNotGrantorException
import com.openbank.delegation.application.usecase.DelegationResourceOwnershipException
import com.openbank.delegation.application.usecase.DelegationScaException
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
 * 422, not 403: the request is well-formed and the caller is who they say they are — the
 * *content* of the grant is what cannot be accepted. Same class as the eligibility gate.
 */
@Provider
class DelegationResourceOwnershipExceptionMapper : ExceptionMapper<DelegationResourceOwnershipException> {
    override fun toResponse(exception: DelegationResourceOwnershipException): Response =
        Response.status(422).entity(errorBody(422, exception.message)).build()
}
