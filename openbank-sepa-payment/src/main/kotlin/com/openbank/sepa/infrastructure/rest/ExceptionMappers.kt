// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.sepa.application.port.out.DocumentTemplateUnavailableException
import com.openbank.sepa.application.usecase.InvalidSepaPaymentStateTransitionException
import com.openbank.sepa.application.usecase.PaymentNotCompletedException
import com.openbank.sepa.application.usecase.SepaPaymentNotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant

private const val HTTP_CONFLICT = 409
private const val HTTP_BAD_GATEWAY = 502

@Provider
class SepaPaymentNotFoundMapper : ExceptionMapper<SepaPaymentNotFoundException> {
    override fun toResponse(exception: SepaPaymentNotFoundException): Response = Response.status(404)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                404,
                ErrorCode.NOT_FOUND.code,
                exception.message ?: "Not found",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

@Provider
class InvalidSepaPaymentStateTransitionMapper : ExceptionMapper<InvalidSepaPaymentStateTransitionException> {
    override fun toResponse(exception: InvalidSepaPaymentStateTransitionException): Response = Response.status(409)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                409,
                ErrorCode.CONFLICT.code,
                exception.message ?: "Conflict",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).

@Provider
class PaymentNotCompletedMapper : ExceptionMapper<PaymentNotCompletedException> {
    override fun toResponse(exception: PaymentNotCompletedException): Response = Response.status(HTTP_CONFLICT)
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
}

@Provider
class DocumentTemplateUnavailableMapper : ExceptionMapper<DocumentTemplateUnavailableException> {
    override fun toResponse(exception: DocumentTemplateUnavailableException): Response =
        Response.status(HTTP_BAD_GATEWAY)
            .entity(
                ApiError(
                    Ids.randomId().toString(),
                    HTTP_BAD_GATEWAY,
                    ErrorCode.INTERNAL_ERROR.code,
                    exception.message ?: "Confirmation document unavailable",
                    timestamp = Instant.now(),
                ),
            )
            .build()
}
