// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.port.`in`.PaymentNotSettledException
import com.openbank.domestic.application.port.out.PaymentConfirmationRenderException
import com.openbank.domestic.application.usecase.DomesticPaymentIdempotencyConflictException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant

// DomesticPaymentNotFoundMapper / InvalidDomesticPaymentStateTransitionMapper (404/409) removed
// here (#10911/#11059 phase 3): DomesticPaymentNotFoundException/
// InvalidDomesticPaymentStateTransitionException now extend the libs-domain
// ResourceNotFoundException/ResourceConflictException bases, handled by libs-runtime's
// ResourceNotFoundExceptionMapper/ResourceConflictExceptionMapper — same status, code and
// ApiError shape. See DomesticPaymentExceptionMapperEquivalenceTest.

/** A key is replayable only for the exact normalized command and authenticated actor scope. */
@Provider
class DomesticPaymentIdempotencyConflictMapper : ExceptionMapper<DomesticPaymentIdempotencyConflictException> {
    override fun toResponse(exception: DomesticPaymentIdempotencyConflictException): Response {
        val detail = requireNotNull(exception.message)
        return Response.status(Response.Status.CONFLICT)
            .entity(
                mapOf(
                    "type" to "urn:openbank:error:idempotency-key-reused",
                    "title" to "Idempotency key reused",
                    "status" to Response.Status.CONFLICT.statusCode,
                    "detail" to detail,
                    "code" to "IDEMPOTENCY_KEY_REUSED",
                    "error" to detail,
                ),
            )
            .type("application/problem+json")
            .build()
    }
}

@Provider
class PaymentNotSettledMapper : ExceptionMapper<PaymentNotSettledException> {
    override fun toResponse(exception: PaymentNotSettledException): Response = Response.status(HTTP_CONFLICT)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                HTTP_CONFLICT,
                ErrorCode.CONFLICT.code,
                exception.message ?: "Conflict",
                timestamp = Instant.now(),
            ),
        )
        .build()

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}

@Provider
class PaymentConfirmationRenderMapper : ExceptionMapper<PaymentConfirmationRenderException> {
    override fun toResponse(exception: PaymentConfirmationRenderException): Response = Response.status(HTTP_BAD_GATEWAY)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                HTTP_BAD_GATEWAY,
                "DOCUMENT_SERVICE_UNAVAILABLE",
                exception.message ?: "Confirmation rendering is temporarily unavailable",
                timestamp = Instant.now(),
            ),
        )
        .build()

    private companion object {
        const val HTTP_BAD_GATEWAY = 502
    }
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).

/** ADR-0335 D5: a scoped or undeclared initiator paying from an account it may not use. */
@Provider
class InitiatorScopeViolationMapper :
    ExceptionMapper<com.openbank.domestic.application.usecase.InitiatorScopeViolationException> {
    override fun toResponse(e: com.openbank.domestic.application.usecase.InitiatorScopeViolationException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(
            ApiError(
                traceId = Ids.randomId().toString(),
                status = 403,
                code = ErrorCode.FORBIDDEN.code,
                message = e.message ?: "Debtor account outside caller scope",
                timestamp = Instant.now(),
            ),
        ).build()
}
