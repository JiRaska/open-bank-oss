// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.util.UUID

/**
 * Service-wide default exception mappers. Auto-registered for every service that pulls
 * in openbank-libs (via Jandex index on the libs JAR).
 *
 * Services may register narrower mappers for their own business exceptions; JAX-RS picks
 * the most specific mapper, so adding `AccountNotFoundExceptionMapper : ExceptionMapper<AccountNotFoundException>`
 * still wins over the generic [NoSuchElementExceptionMapper] here.
 *
 * traceId is taken from the correlation MDC key set by [com.openbank.libs.web.CorrelationIdRequestFilter]
 * so error responses correlate to log lines for the same request. Falls back to a fresh UUID.
 */
private fun traceId(): String = (MDC.get("correlationId") as? String) ?: UUID.randomUUID().toString()

private fun apiError(status: Int, code: String, message: String, details: List<FieldError>? = null) =
    ApiError(traceId = traceId(), status = status, code = code, message = message, details = details)

@Provider
class IllegalArgumentExceptionMapper : ExceptionMapper<IllegalArgumentException> {
    override fun toResponse(exception: IllegalArgumentException): Response = Response.status(400)
        .entity(apiError(400, ErrorCode.VALIDATION_ERROR.code, exception.message ?: "Invalid request"))
        .build()
}

@Provider
class IllegalStateExceptionMapper : ExceptionMapper<IllegalStateException> {
    private val log = Logger.getLogger(IllegalStateExceptionMapper::class.java)
    override fun toResponse(exception: IllegalStateException): Response {
        log.warnf(exception, "business rule violation: %s", exception.message)
        return Response.status(422)
            .entity(apiError(422, "BUSINESS_RULE_VIOLATION", exception.message ?: "Business rule violation"))
            .build()
    }
}

@Provider
class NoSuchElementExceptionMapper : ExceptionMapper<NoSuchElementException> {
    override fun toResponse(exception: NoSuchElementException): Response = Response.status(404)
        .entity(apiError(404, ErrorCode.NOT_FOUND.code, exception.message ?: "Resource not found"))
        .build()
}

// ConstraintViolationExceptionMapper intentionally NOT in libs:
// jakarta.validation.ConstraintViolationException is only on the runtime classpath of
// services that pull in quarkus-hibernate-validator. Auto-registering it here would crash
// every other service at ArC init with a ClassNotFoundException. Services that need
// bean-validation field-level mapping should register their own mapper.

@Provider
class WebApplicationExceptionMapper : ExceptionMapper<WebApplicationException> {
    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500
        val code = when (status) {
            401 -> ErrorCode.UNAUTHORIZED.code
            403 -> ErrorCode.FORBIDDEN.code
            404 -> ErrorCode.NOT_FOUND.code
            409 -> ErrorCode.CONFLICT.code
            else -> "HTTP_$status"
        }
        return Response.status(status)
            .entity(apiError(status, code, exception.message ?: "Request failed"))
            .build()
    }
}

/**
 * Last-resort mapper. Logs the full stack trace and returns a sanitised 500 — never leak
 * internal exception messages to the client, since they may contain SQL fragments,
 * stack traces or PII.
 */
@Provider
class GenericExceptionMapper : ExceptionMapper<Exception> {
    private val log = Logger.getLogger(GenericExceptionMapper::class.java)
    override fun toResponse(exception: Exception): Response {
        val id = traceId()
        log.errorf(exception, "Unhandled exception (traceId=%s)", id)
        return Response.status(500)
            .entity(
                ApiError(
                    traceId = id,
                    status = 500,
                    code = ErrorCode.INTERNAL_ERROR.code,
                    message = "An unexpected error occurred. Please contact support with traceId=$id.",
                ),
            )
            .build()
    }
}
