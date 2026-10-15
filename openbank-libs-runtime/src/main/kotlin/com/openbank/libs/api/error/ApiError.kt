// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

/**
 * The fleet-wide error envelope.
 *
 * [timestamp] is deliberately **not defaulted** (#3874). It used to default to [Instant.EPOCH], and
 * no call site in the fleet ever passed it, so every error response the platform served carried
 * `1970-01-01T00:00:00Z` — a syntactically valid value that nothing fails on, which is why it
 * survived. The support path this envelope exists for ("contact support with traceId=…") lost the
 * one field that would place a trace in time.
 *
 * The fix is the *absence* of a default, not a better one. `Instant.now()` as a default would have
 * made every existing call site silently correct and left no way to distinguish "the caller meant
 * now" from "the caller forgot" — the same failure mode, one value to the right. It would also put
 * a hidden, unmockable clock read inside a framework-free domain type whose equality this repo's
 * tests rely on. A required argument makes the compiler enumerate every construction site, and an
 * epoch timestamp can now only appear because someone wrote it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val traceId: String,
    val status: Int,
    val code: String,
    val message: String,
    val timestamp: Instant,
    val details: List<FieldError>? = null,
)

data class FieldError(val field: String, val message: String, val rejectedValue: Any? = null)

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_ENTITY = 422
private const val HTTP_INTERNAL_ERROR = 500
private const val HTTP_SERVICE_UNAVAILABLE = 503

enum class ErrorCode(val httpStatus: Int, val code: String) {
    ACCOUNT_NOT_FOUND(HTTP_NOT_FOUND, "ACCOUNT_NOT_FOUND"),
    ACCOUNT_ALREADY_EXISTS(HTTP_CONFLICT, "ACCOUNT_ALREADY_EXISTS"),
    ACCOUNT_CLOSED(HTTP_UNPROCESSABLE_ENTITY, "ACCOUNT_CLOSED"),
    ACCOUNT_FROZEN(HTTP_UNPROCESSABLE_ENTITY, "ACCOUNT_FROZEN"),
    INSUFFICIENT_FUNDS(HTTP_UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS"),
    INVALID_CURRENCY(HTTP_BAD_REQUEST, "INVALID_CURRENCY"),
    INVALID_IBAN(HTTP_BAD_REQUEST, "INVALID_IBAN"),
    INVALID_AMOUNT(HTTP_BAD_REQUEST, "INVALID_AMOUNT"),
    DUPLICATE_REQUEST(HTTP_CONFLICT, "DUPLICATE_REQUEST"),
    IDEMPOTENCY_KEY_REUSED(HTTP_CONFLICT, "IDEMPOTENCY_KEY_REUSED"),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HTTP_CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS"),
    VALIDATION_ERROR(HTTP_BAD_REQUEST, "VALIDATION_ERROR"),
    NOT_FOUND(HTTP_NOT_FOUND, "NOT_FOUND"),
    CONFLICT(HTTP_CONFLICT, "CONFLICT"),
    UNAUTHORIZED(HTTP_UNAUTHORIZED, "UNAUTHORIZED"),
    FORBIDDEN(HTTP_FORBIDDEN, "FORBIDDEN"),
    INTERNAL_ERROR(HTTP_INTERNAL_ERROR, "INTERNAL_ERROR"),
    POLICY_DECISION_POINT_UNAVAILABLE(HTTP_SERVICE_UNAVAILABLE, "POLICY_DECISION_POINT_UNAVAILABLE"),
}
