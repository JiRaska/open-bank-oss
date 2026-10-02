// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.error

import java.time.Duration

/** One rejected input, safe to show the caller. The rejected VALUE is deliberately not carried. */
data class Violation(val field: String, val message: String, val code: String? = null)

/**
 * The base of the fleet's typed error hierarchy (ADR-0326). libs-runtime maps every subclass through
 * ONE mapper, so a service throws one of these instead of writing an `ExceptionMapper` of its own.
 *
 * Two messages, kept apart on purpose:
 *
 *  - [clientMessage] is the only text that leaves the process. Write it for the caller; it must not
 *    name a table, a class, a policy rule or another party's data.
 *  - [message] (the `Throwable` one) is [internalDetail] when given, and is only ever logged.
 *
 * [category] is fixed by the subclass and [errorCode] must belong to it. A mismatch is a programming
 * error, and the mapper answers it as an internal fault rather than trusting either side — the
 * constructor does not throw, because an exception that fails to construct would surface as some
 * OTHER exception and hide the one that was meant.
 *
 * Deliberately NOT a subclass of `IllegalArgumentException` / `IllegalStateException` /
 * `NoSuchElementException`: libs-runtime's generic mappers for those would compete with the
 * dedicated one (issue #526).
 */
abstract class DomainException(
    val category: ErrorCategory,
    val errorCode: ErrorCode,
    val clientMessage: String,
    val violations: List<Violation> = emptyList(),
    val retryAfter: Duration? = null,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(internalDetail ?: clientMessage, cause) {

    /** True when [errorCode] is well-formed and belongs to this exception's [category]. */
    val isConsistent: Boolean
        get() = errorCode.category == category && ErrorCode.isWellFormed(errorCode.code)
}

/** The request is malformed or fails validation. */
open class ValidationFailure(
    clientMessage: String,
    violations: List<Violation> = emptyList(),
    errorCode: ErrorCode = PlatformErrorCode.VALIDATION_ERROR,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.VALIDATION,
    errorCode,
    clientMessage,
    violations,
    internalDetail = internalDetail,
    cause = cause,
)

/** No valid credentials. The default message says nothing about which check failed. */
open class AuthenticationRequired(
    clientMessage: String = "Authentication required",
    errorCode: ErrorCode = PlatformErrorCode.UNAUTHORIZED,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.UNAUTHENTICATED,
    errorCode,
    clientMessage,
    internalDetail = internalDetail,
    cause = cause,
)

/** The caller is known and not allowed. Put the reason in [internalDetail], not in the message. */
open class AccessDenied(
    clientMessage: String = "Access denied",
    errorCode: ErrorCode = PlatformErrorCode.FORBIDDEN,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(ErrorCategory.FORBIDDEN, errorCode, clientMessage, internalDetail = internalDetail, cause = cause)

/** The addressed resource does not exist. */
open class NotFound(
    clientMessage: String,
    errorCode: ErrorCode = PlatformErrorCode.NOT_FOUND,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(ErrorCategory.NOT_FOUND, errorCode, clientMessage, internalDetail = internalDetail, cause = cause)

/** The request conflicts with the resource's current state. */
open class Conflict(
    clientMessage: String,
    errorCode: ErrorCode = PlatformErrorCode.CONFLICT,
    retryAfter: Duration? = null,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.CONFLICT,
    errorCode,
    clientMessage,
    retryAfter = retryAfter,
    internalDetail = internalDetail,
    cause = cause,
)

/** The request is well-formed and a business rule refuses it. */
open class RuleViolation(
    clientMessage: String,
    errorCode: ErrorCode = PlatformErrorCode.BUSINESS_RULE_VIOLATION,
    violations: List<Violation> = emptyList(),
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.RULE_VIOLATION,
    errorCode,
    clientMessage,
    violations,
    internalDetail = internalDetail,
    cause = cause,
)

/** The caller exceeded a rate or attempt limit; [retryAfter] becomes the `Retry-After` header. */
open class RateLimitExceeded(
    clientMessage: String = "Too many requests",
    retryAfter: Duration? = null,
    errorCode: ErrorCode = PlatformErrorCode.RATE_LIMIT_EXCEEDED,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.RATE_LIMITED,
    errorCode,
    clientMessage,
    retryAfter = retryAfter,
    internalDetail = internalDetail,
    cause = cause,
)

/**
 * A dependency answered and the answer was unusable. There is no platform default code: the caller
 * names WHICH dependency through a code of its own bounded context.
 */
open class UpstreamFailure(
    clientMessage: String,
    errorCode: ErrorCode,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.UPSTREAM_FAILURE,
    errorCode,
    clientMessage,
    internalDetail = internalDetail,
    cause = cause,
)

/** Temporarily unable to answer. */
open class Unavailable(
    clientMessage: String = "The service is temporarily unavailable",
    retryAfter: Duration? = null,
    errorCode: ErrorCode = PlatformErrorCode.SERVICE_UNAVAILABLE,
    internalDetail: String? = null,
    cause: Throwable? = null,
) : DomainException(
    ErrorCategory.UNAVAILABLE,
    errorCode,
    clientMessage,
    retryAfter = retryAfter,
    internalDetail = internalDetail,
    cause = cause,
)

/**
 * Request-validation helpers that throw [ValidationFailure] — the typed replacement for
 * `requireNotNull` / `require` at a resource boundary. They exist so that request validation keeps
 * answering 400 on the day the generic `IllegalArgumentException` mapper stops doing so (ADR-0326
 * phase c): a `require()` in a use case and a `require()` on a request parameter are the same
 * exception today, and only one of them is the caller's fault.
 */
fun <T : Any> requireParam(value: T?, name: String): T = value ?: throw ValidationFailure(
    clientMessage = "Parameter '$name' is required",
    violations = listOf(Violation(field = name, message = "is required")),
)

/** Throws [ValidationFailure] with [lazyMessage] (client-safe) unless [condition] holds. */
inline fun requireValid(condition: Boolean, field: String? = null, lazyMessage: () -> String) {
    if (!condition) {
        val message = lazyMessage()
        throw ValidationFailure(
            clientMessage = message,
            violations = field?.let { listOf(Violation(field = it, message = message)) } ?: emptyList(),
        )
    }
}
