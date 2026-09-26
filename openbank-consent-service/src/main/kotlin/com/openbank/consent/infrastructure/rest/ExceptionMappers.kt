// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest

import com.openbank.consent.application.usecase.*
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant

private fun errorResponse(code: ErrorCode, message: String) = ApiError(
    traceId = Ids.randomId().toString(),
    status = code.httpStatus,
    code = code.code,
    message = message,
    timestamp = Instant.now(),
)

// ConsentNotFoundException / ConsentAlreadyActiveException: mapped by libs-runtime's
// Resource{NotFound,Conflict}ExceptionMapper since #10911 phase 2 -- see
// ConsentExceptionMapperEquivalenceTest for the byte-for-byte proof.

@Provider
class ConsentNotOwnedMapper : ExceptionMapper<ConsentNotOwnedByPartyException> {
    override fun toResponse(e: ConsentNotOwnedByPartyException): Response =
        Response.status(403).entity(errorResponse(ErrorCode.FORBIDDEN, e.message ?: "Forbidden")).build()
}

@Provider
class ConsentGranteeMismatchMapper : ExceptionMapper<ConsentGranteeMismatchException> {
    override fun toResponse(e: ConsentGranteeMismatchException): Response =
        Response.status(ErrorCode.FORBIDDEN.httpStatus)
            .entity(errorResponse(ErrorCode.FORBIDDEN, e.message ?: "Forbidden")).build()
}

@Provider
class ConsentScaChallengeNotFoundMapper : ExceptionMapper<ConsentScaChallengeNotFoundException> {
    override fun toResponse(e: ConsentScaChallengeNotFoundException): Response = Response.status(
        422,
    ).entity(errorResponse(ErrorCode.VALIDATION_ERROR, e.message ?: "SCA challenge not found")).build()
}

@Provider
class ConsentScaVerificationUnavailableMapper : ExceptionMapper<ConsentScaVerificationUnavailableException> {
    override fun toResponse(e: ConsentScaVerificationUnavailableException): Response = Response.status(503)
        .entity(
            ApiError(
                traceId = Ids.randomId().toString(),
                status = 503,
                code = "SERVICE_UNAVAILABLE",
                message = "SCA verification is temporarily unavailable",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

@Provider
class ConsentScaChallengeMismatchMapper : ExceptionMapper<ConsentScaChallengeMismatchException> {
    override fun toResponse(e: ConsentScaChallengeMismatchException): Response = Response.status(
        422,
    ).entity(errorResponse(ErrorCode.VALIDATION_ERROR, e.message ?: "SCA challenge mismatch")).build()
}

@Provider
class ConsentScaNotCompletedMapper : ExceptionMapper<ConsentScaNotCompletedException> {
    override fun toResponse(e: ConsentScaNotCompletedException): Response = Response.status(
        422,
    ).entity(errorResponse(ErrorCode.VALIDATION_ERROR, e.message ?: "SCA challenge not completed")).build()
}
