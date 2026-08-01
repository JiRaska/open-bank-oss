// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.authz.PolicyDecisionException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.time.DateTimeException
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

/**
 * `DateTimeException` (and its `DateTimeParseException` subclass) → **400**.
 *
 * It extends `RuntimeException`, **not** `IllegalArgumentException`, so before this it fell all the
 * way through to [GenericExceptionMapper] and every unparseable date rendered as a 500. Resources
 * parse dates straight off the query string —
 * `date?.let { LocalDate.parse(it) }` — and `?date=` or `?date=null` are non-null strings, so the
 * `?.let` runs and `parse` throws.
 *
 * The first full authenticated-fuzz run hit this on five money-path endpoints (#3038): balance
 * reconciliation, fx CNB ingest, interest accrue-all / capitalize / accruals. A client sending a bad
 * date is a client error, and reporting it as 5xx inflates the error budget of those services and
 * keeps the fuzz lane red forever.
 *
 * Safe to map globally, unlike a general "bad input" guess: a `DateTimeException` reaching a
 * *resource boundary* is always a rejected value, never a server fault. A genuine server-side clock
 * or zone bug would not surface here as an escaping exception from request handling.
 */
@Provider
class DateTimeExceptionMapper : ExceptionMapper<DateTimeException> {
    override fun toResponse(exception: DateTimeException): Response = Response.status(400)
        .entity(apiError(400, ErrorCode.VALIDATION_ERROR.code, exception.message ?: "Invalid date or time value"))
        .build()
}

@Provider
class NoSuchElementExceptionMapper : ExceptionMapper<NoSuchElementException> {
    override fun toResponse(exception: NoSuchElementException): Response = Response.status(404)
        .entity(apiError(404, ErrorCode.NOT_FOUND.code, exception.message ?: "Resource not found"))
        .build()
}

// ADR-0155: a checker can never decide their own PendingApproval — ApprovalStore.decide
// enforces this itself (defense-in-depth), surfaced here as a plain 403. Formerly duplicated
// verbatim across 10+ services (issue #1394) plus a divergent {"code","message"}-shaped copy in
// notification-service; a per-service copy of this exact type would collide non-deterministically
// with this one (issue #526's defect class), so this is now the ONLY registered mapper for it.
@Provider
class SelfApprovalNotAllowedMapper : ExceptionMapper<SelfApprovalNotAllowedException> {
    override fun toResponse(exception: SelfApprovalNotAllowedException): Response =
        Response.status(ErrorCode.FORBIDDEN.httpStatus)
            .entity(
                apiError(ErrorCode.FORBIDDEN.httpStatus, ErrorCode.FORBIDDEN.code, exception.message ?: "Forbidden"),
            )
            .build()
}

// decide()/markExecuted() reject re-deciding or re-consuming an approval that isn't in the
// expected status (e.g. an EXECUTED approval flipped back to APPROVED and replayed). See
// SelfApprovalNotAllowedMapper above for why this lives here instead of per-service.
@Provider
class InvalidApprovalStateMapper : ExceptionMapper<InvalidApprovalStateException> {
    override fun toResponse(exception: InvalidApprovalStateException): Response =
        Response.status(ErrorCode.CONFLICT.httpStatus)
            .entity(apiError(ErrorCode.CONFLICT.httpStatus, ErrorCode.CONFLICT.code, exception.message ?: "Conflict"))
            .build()
}

// A PDP outage (OPA sidecar unreachable, or no PolicyDecisionPoint wired while enforcing) is an
// availability failure, not a client error. AuthorizeInterceptor throws PolicyDecisionException; map
// it to 503 with its own code so an outage reads as "the platform is broken", not laundered into a
// 422 BUSINESS_RULE_VIOLATION ("the caller sent something invalid"). Keyed on the concrete domain
// type, this is immune to whatever the Kotlin suspend/coroutine bridge does to WebApplicationException
// subtypes at the JAX-RS boundary — the reason a thrown ServiceUnavailableException (503) was
// surfacing as 422 (issue #1797).
@Provider
class PolicyDecisionExceptionMapper : ExceptionMapper<PolicyDecisionException> {
    override fun toResponse(exception: PolicyDecisionException): Response =
        Response.status(ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.httpStatus)
            .entity(
                apiError(
                    ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.httpStatus,
                    ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.code,
                    exception.message ?: "Policy decision point unavailable",
                ),
            )
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
