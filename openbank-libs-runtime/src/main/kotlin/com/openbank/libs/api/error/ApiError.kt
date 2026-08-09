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

enum class ErrorCode(val httpStatus: Int, val code: String) {
    ACCOUNT_NOT_FOUND(404, "ACCOUNT_NOT_FOUND"),
    ACCOUNT_ALREADY_EXISTS(409, "ACCOUNT_ALREADY_EXISTS"),
    ACCOUNT_CLOSED(422, "ACCOUNT_CLOSED"),
    ACCOUNT_FROZEN(422, "ACCOUNT_FROZEN"),
    INSUFFICIENT_FUNDS(422, "INSUFFICIENT_FUNDS"),
    INVALID_CURRENCY(400, "INVALID_CURRENCY"),
    INVALID_IBAN(400, "INVALID_IBAN"),
    INVALID_AMOUNT(400, "INVALID_AMOUNT"),
    DUPLICATE_REQUEST(409, "DUPLICATE_REQUEST"),
    VALIDATION_ERROR(400, "VALIDATION_ERROR"),
    NOT_FOUND(404, "NOT_FOUND"),
    CONFLICT(409, "CONFLICT"),
    UNAUTHORIZED(401, "UNAUTHORIZED"),
    FORBIDDEN(403, "FORBIDDEN"),
    INTERNAL_ERROR(500, "INTERNAL_ERROR"),
    POLICY_DECISION_POINT_UNAVAILABLE(503, "POLICY_DECISION_POINT_UNAVAILABLE"),
}
