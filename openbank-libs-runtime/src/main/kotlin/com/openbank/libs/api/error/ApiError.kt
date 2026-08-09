// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val traceId: String,
    val status: Int,
    val code: String,
    val message: String,
    val timestamp: Instant = Instant.EPOCH,
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
