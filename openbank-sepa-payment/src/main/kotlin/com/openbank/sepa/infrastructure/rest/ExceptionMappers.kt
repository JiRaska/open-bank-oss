// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.sepa.application.port.out.DocumentTemplateUnavailableException
import com.openbank.sepa.application.usecase.PaymentNotCompletedException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant

private const val HTTP_CONFLICT = 409
private const val HTTP_BAD_GATEWAY = 502

// SepaPaymentNotFoundMapper / InvalidSepaPaymentStateTransitionMapper (404/409) removed here
// (#10911/#11059 phase 3): SepaPaymentNotFoundException/InvalidSepaPaymentStateTransitionException
// now extend the libs-domain ResourceNotFoundException/ResourceConflictException bases, handled
// by libs-runtime's ResourceNotFoundExceptionMapper/ResourceConflictExceptionMapper — same status,
// code and ApiError shape. See SepaPaymentExceptionMapperEquivalenceTest.

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
