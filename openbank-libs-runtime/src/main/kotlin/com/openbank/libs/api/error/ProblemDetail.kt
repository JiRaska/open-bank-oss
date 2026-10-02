// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.libs.domain.error.ErrorCategory
import java.time.Instant

/**
 * The fleet error body (ADR-0326): RFC 9457 problem details, extended, and a SUPERSET of [ApiError].
 *
 * One document carries both vocabularies so that no existing consumer has to change on the day a
 * service moves an exception onto the typed hierarchy:
 *
 *  - RFC 9457 members — [type], [title], [status], [detail], [instance];
 *  - extensions — [code] (the stable machine-readable code), [correlationId], [retryable],
 *    [violations];
 *  - the [ApiError] members under their existing names — [traceId] (= [correlationId]), [message]
 *    (= [detail]), [timestamp], [details] (= [violations]), plus `status` and `code`, which the two
 *    shapes already share.
 *
 * Every text member is client-safe by construction: [title] comes from the catalogue entry and
 * [detail] from `DomainException.clientMessage`. Nothing here is ever an exception message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val correlationId: String,
    val retryable: Boolean,
    val violations: List<ProblemViolation>? = null,
    val traceId: String,
    val message: String,
    val timestamp: Instant,
    val details: List<FieldError>? = null,
) {
    companion object {
        const val MEDIA_TYPE = "application/problem+json"

        private const val TYPE_PREFIX = "urn:openbank:error:"
        private const val INSTANCE_PREFIX = "urn:openbank:error-occurrence:"

        /** `IDEMPOTENCY_KEY_REUSED` -> `urn:openbank:error:idempotency-key-reused`. */
        fun typeOf(code: String): String = TYPE_PREFIX + code.lowercase().replace('_', '-')

        fun instanceOf(correlationId: String): String = INSTANCE_PREFIX + correlationId
    }
}

/** One rejected input. [code] is an optional machine-readable reason; the rejected value is never echoed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemViolation(val field: String, val message: String, val code: String? = null)

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL = 500
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_UNAVAILABLE = 503

/**
 * The ONE place a category becomes an HTTP status. Exhaustive `when`, no `else`: adding a category
 * to libs-domain does not compile until it is given a status here.
 */
fun ErrorCategory.httpStatus(): Int = when (this) {
    ErrorCategory.VALIDATION -> HTTP_BAD_REQUEST
    ErrorCategory.UNAUTHENTICATED -> HTTP_UNAUTHORIZED
    ErrorCategory.FORBIDDEN -> HTTP_FORBIDDEN
    ErrorCategory.NOT_FOUND -> HTTP_NOT_FOUND
    ErrorCategory.CONFLICT -> HTTP_CONFLICT
    ErrorCategory.RULE_VIOLATION -> HTTP_UNPROCESSABLE
    ErrorCategory.RATE_LIMITED -> HTTP_TOO_MANY_REQUESTS
    ErrorCategory.UPSTREAM_FAILURE -> HTTP_BAD_GATEWAY
    ErrorCategory.UNAVAILABLE -> HTTP_UNAVAILABLE
    ErrorCategory.INTERNAL -> HTTP_INTERNAL
}
