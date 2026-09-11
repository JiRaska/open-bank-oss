// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.rest

import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.application.usecase.CaseCallerMismatchException
import com.openbank.kyb.application.usecase.CaseNotFoundException
import com.openbank.kyb.application.usecase.InvitationNotFoundException
import com.openbank.kyb.domain.model.CaseTransitionException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

// IllegalArgumentException (400) and IllegalStateException are mapped by libs-runtime (#526);
// only the service-specific exceptions get a mapper here.

private fun error(status: Int, code: String, message: String?): Response = Response.status(status)
    .entity(mapOf("error" to code, "message" to message))
    .type(MediaType.APPLICATION_JSON)
    .build()

@Provider
class CaseNotFoundMapper : ExceptionMapper<CaseNotFoundException> {
    override fun toResponse(e: CaseNotFoundException): Response = error(NOT_FOUND, "CASE_NOT_FOUND", e.message)
}

@Provider
class InvitationNotFoundMapper : ExceptionMapper<InvitationNotFoundException> {
    override fun toResponse(e: InvitationNotFoundException): Response =
        error(NOT_FOUND, "INVITATION_NOT_FOUND", e.message)
}

@Provider
class CaseCallerMismatchMapper : ExceptionMapper<CaseCallerMismatchException> {
    override fun toResponse(e: CaseCallerMismatchException): Response = error(FORBIDDEN, "FORBIDDEN", e.message)
}

@Provider
class CaseTransitionMapper : ExceptionMapper<CaseTransitionException> {
    override fun toResponse(e: CaseTransitionException): Response = error(CONFLICT, "INVALID_TRANSITION", e.message)
}

@Provider
class RegistryUnavailableMapper : ExceptionMapper<RegistryUnavailableException> {
    override fun toResponse(e: RegistryUnavailableException): Response =
        error(UNAVAILABLE, "REGISTRY_UNAVAILABLE", e.message)
}

private const val NOT_FOUND = 404
private const val FORBIDDEN = 403
private const val CONFLICT = 409
private const val UNAVAILABLE = 503
