// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.domain.error.DomainException
import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.PlatformErrorCode
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.time.Instant

private const val SERVER_ERROR_FLOOR = 500
private const val LOG_FIELD_ERROR_CODE = "errorCode"

/**
 * The ONE mapper for the typed error hierarchy (ADR-0326): every [DomainException] subclass, in
 * every service, is rendered here as a [ProblemDetail]. JAX-RS resolves a mapper by the nearest
 * supertype, so a service that throws a subclass needs no mapper of its own — and must not register
 * one for [DomainException] itself (`check-exception-mapper-collision.sh`, issue #526).
 *
 * What reaches the caller is the catalogue entry and `clientMessage`, never `exception.message`:
 * that one is the internal detail and is logged here instead.
 *
 * Media type: `application/problem+json` when the request's `Accept` names it, otherwise
 * `application/json` — the body is the same document either way. A consumer written against the
 * `ApiError` envelope keeps receiving the content type it parses today, and one written against
 * RFC 9457 can ask for it. It must list `problem+json` BESIDE a type the endpoint produces
 * (`Accept: application/problem+json, application/json`): the framework negotiates `Accept` against
 * the resource's `@Produces` before the method runs, so an `Accept` naming only `problem+json` is a
 * 406 that never reaches any mapper (measured in `DomainExceptionMapperIT`).
 */
@Provider
class DomainExceptionMapper : ExceptionMapper<DomainException> {
    private val log = Logger.getLogger(DomainExceptionMapper::class.java)

    /** Null outside a container (unit tests construct the mapper directly). */
    @Context
    @JvmField
    var headers: HttpHeaders? = null

    override fun toResponse(exception: DomainException): Response {
        val correlationId = (MDC.get("correlationId") as? String) ?: Ids.randomId().toString()
        val problem = if (exception.isConsistent) render(exception, correlationId) else misuse(exception, correlationId)
        val response = Response.status(problem.status).entity(problem).type(mediaType())
        if (exception.isConsistent) {
            exception.retryAfter?.let { response.header(HttpHeaders.RETRY_AFTER, it.seconds.coerceAtLeast(1)) }
        }
        return response.build()
    }

    private fun render(exception: DomainException, correlationId: String): ProblemDetail {
        val code = exception.errorCode
        val status = exception.category.httpStatus()
        // A structured log field as well as text, so a log query can group by code without a regex.
        MDC.put(LOG_FIELD_ERROR_CODE, code.code)
        try {
            if (status >= SERVER_ERROR_FLOOR) {
                log.errorf(exception, "%s (correlationId=%s): %s", code.code, correlationId, exception.message)
            } else {
                log.infof("%s (correlationId=%s): %s", code.code, correlationId, exception.message)
            }
        } finally {
            MDC.remove(LOG_FIELD_ERROR_CODE)
        }
        ApiErrorMetrics.domainError(code.code, exception.category.name, status)
        return ProblemDetail(
            type = ProblemDetail.typeOf(code.code),
            title = code.title,
            status = status,
            detail = exception.clientMessage,
            instance = ProblemDetail.instanceOf(correlationId),
            code = code.code,
            correlationId = correlationId,
            retryable = code.retryable,
            violations = exception.violations.map { ProblemViolation(it.field, it.message, it.code) }.ifEmpty { null },
            traceId = correlationId,
            message = exception.clientMessage,
            timestamp = Instant.now(),
            details = exception.violations.map { FieldError(it.field, it.message) }.ifEmpty { null },
        )
    }

    /**
     * The exception's code is malformed or belongs to another category than its class. Neither side
     * can be trusted, so the caller gets a plain internal error and nothing the exception carried.
     */
    private fun misuse(exception: DomainException, correlationId: String): ProblemDetail {
        val internal = PlatformErrorCode.INTERNAL_ERROR
        val status = ErrorCategory.INTERNAL.httpStatus()
        log.errorf(
            exception,
            "error-model misuse: %s carries a code of category %s (correlationId=%s)",
            exception.javaClass.name,
            exception.errorCode.category,
            correlationId,
        )
        ApiErrorMetrics.domainError(internal.code, ErrorCategory.INTERNAL.name, status)
        return ProblemDetail(
            type = ProblemDetail.typeOf(internal.code),
            title = internal.title,
            status = status,
            detail = internal.title,
            instance = ProblemDetail.instanceOf(correlationId),
            code = internal.code,
            correlationId = correlationId,
            retryable = internal.retryable,
            traceId = correlationId,
            message = internal.title,
            timestamp = Instant.now(),
        )
    }

    private fun mediaType(): String {
        val wantsProblem = try {
            headers?.acceptableMediaTypes.orEmpty().any {
                it.type == "application" && it.subtype == "problem+json"
            }
        } catch (outsideRequest: IllegalStateException) {
            log.debug("no request scope while reading Accept; answering application/json", outsideRequest)
            false
        }
        return if (wantsProblem) ProblemDetail.MEDIA_TYPE else MediaType.APPLICATION_JSON
    }
}
