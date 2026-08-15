// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.application.usecase.InvalidPartyCaseTransitionException
import com.openbank.pid.application.usecase.PartyAlreadyExistsException
import com.openbank.pid.application.usecase.PartyNotFoundException
import com.openbank.pid.application.usecase.RelationshipAlreadyExistsException
import com.openbank.pid.application.usecase.VerificationCaseNotFoundException
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

@Provider
class PartyNotFoundMapper : ExceptionMapper<PartyNotFoundException> {
    override fun toResponse(e: PartyNotFoundException): Response =
        Response.status(404).entity(errorResponse(ErrorCode.NOT_FOUND, e.message ?: "Party not found")).build()
}

@Provider
class PartyAlreadyExistsMapper : ExceptionMapper<PartyAlreadyExistsException> {
    override fun toResponse(e: PartyAlreadyExistsException): Response =
        Response.status(409).entity(errorResponse(ErrorCode.CONFLICT, e.message ?: "Party already exists")).build()
}

@Provider
class RelationshipAlreadyExistsMapper : ExceptionMapper<RelationshipAlreadyExistsException> {
    override fun toResponse(e: RelationshipAlreadyExistsException): Response = Response.status(
        409,
    ).entity(errorResponse(ErrorCode.CONFLICT, e.message ?: "Relationship already exists")).build()
}

@Provider
class InvalidPartyCaseTransitionMapper : ExceptionMapper<InvalidPartyCaseTransitionException> {
    override fun toResponse(e: InvalidPartyCaseTransitionException): Response = Response.status(
        400,
    ).entity(errorResponse(ErrorCode.VALIDATION_ERROR, e.message ?: "Invalid PID case transition")).build()
}

@Provider
class VerificationCaseNotFoundMapper : ExceptionMapper<VerificationCaseNotFoundException> {
    override fun toResponse(e: VerificationCaseNotFoundException): Response = Response.status(
        Response.Status.NOT_FOUND,
    ).entity(errorResponse(ErrorCode.NOT_FOUND, e.message ?: "Verification case not found")).build()
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
