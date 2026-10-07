// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.error.Violation
import com.openbank.libs.domain.money.InvalidMoneyException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Renders the kernel's typed money failure (ADR-0326) through [DomainExceptionMapper], so it carries
 * its own code — `AMOUNT_SCALE_EXCEEDED`, `CURRENCY_UNSUPPORTED`, `AMOUNT_NOT_POSITIVE` or `VALIDATION_ERROR` — instead of the
 * generic one [IllegalArgumentExceptionMapper] would give it.
 *
 * Status is unchanged (400, the VALIDATION category, exactly what the generic mapper answered) and the
 * body is a `ProblemDetail`, a superset of the `ApiError` the generic mapper rendered. JAX-RS picks the
 * mapper of the nearest supertype, so this one wins over the `IllegalArgumentException` mapper for this
 * type only; it also keeps the answer at 400 once phase c demotes that generic mapper.
 */
@Provider
class InvalidMoneyExceptionMapper : ExceptionMapper<InvalidMoneyException> {

    /** Null outside a container (unit tests construct the mapper directly). */
    @Context
    @JvmField
    var headers: HttpHeaders? = null

    override fun toResponse(exception: InvalidMoneyException): Response =
        DomainExceptionMapper().also { it.headers = headers }.toResponse(asValidationFailure(exception))

    companion object {
        fun asValidationFailure(exception: InvalidMoneyException): ValidationFailure {
            val code = exception.errorCode
            val field = exception.field
            val message = exception.clientMessage
            return ValidationFailure(
                clientMessage = field?.let { "$it: $message" } ?: message,
                violations = listOfNotNull(field?.let { Violation(field = it, message = message, code = code.code) }),
                errorCode = code,
                internalDetail = exception.message,
                cause = exception,
            )
        }
    }
}
