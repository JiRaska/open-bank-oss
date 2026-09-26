// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.application.usecase.InvalidPartyCaseTransitionException
import com.openbank.pid.domain.model.IllegalCaseTransition
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant
import java.util.UUID

private fun errorResponse(code: ErrorCode, message: String) = ApiError(
    traceId = UUID.randomUUID().toString(),
    status = code.httpStatus,
    code = code.code,
    message = message,
    timestamp = Instant.now(),
)

// PartyNotFoundException / PartyAlreadyExistsException / RelationshipAlreadyExistsException /
// VerificationCaseNotFoundException: mapped by libs-runtime's Resource{NotFound,Conflict}
// ExceptionMapper since #10911 phase 2 -- see PidResourceExceptionMapperEquivalenceTest for the
// byte-for-byte proof.

@Provider
class InvalidPartyCaseTransitionMapper : ExceptionMapper<InvalidPartyCaseTransitionException> {
    override fun toResponse(e: InvalidPartyCaseTransitionException): Response = Response.status(
        400,
    ).entity(errorResponse(ErrorCode.VALIDATION_ERROR, e.message ?: "Invalid PID case transition")).build()
}

@Provider
class IllegalCaseTransitionMapper : ExceptionMapper<IllegalCaseTransition> {
    override fun toResponse(e: IllegalCaseTransition): Response = Response.status(
        Response.Status.CONFLICT,
    ).entity(errorResponse(ErrorCode.CONFLICT, e.message ?: "Illegal verification-case transition")).build()
}

@Provider
class PidVerificationExceptionMapper : ExceptionMapper<PidVerificationException> {
    // 422: the EUDI presentation failed a verification check. Message is safe (no PII / no token).
    override fun toResponse(e: PidVerificationException): Response = Response.status(
        UNPROCESSABLE_ENTITY,
    ).entity(errorResponse(ErrorCode.VALIDATION_ERROR, e.message ?: "EUDI presentation verification failed")).build()
}

private const val UNPROCESSABLE_ENTITY = 422
