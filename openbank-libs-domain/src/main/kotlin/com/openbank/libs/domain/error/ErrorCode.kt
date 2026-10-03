// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.error

/**
 * What KIND of failure an error is (ADR-0326). Closed, and deliberately transport-free: libs-domain
 * names the category, libs-runtime owns the one table that turns a category into an HTTP status.
 * A code therefore cannot choose a status of its own, which is what kept "not found" at 404 in one
 * service and 422 in another.
 *
 * [retryableByDefault] answers "may an identical request succeed later without the caller changing
 * anything" for the category as a whole; a code overrides it where it knows better.
 */
enum class ErrorCategory(val retryableByDefault: Boolean) {
    /** The request is malformed or fails validation; resending it unchanged fails again. */
    VALIDATION(false),

    /** No (valid) credentials were presented. */
    UNAUTHENTICATED(false),

    /** The caller is known and is not allowed to do this. */
    FORBIDDEN(false),

    /** The addressed resource does not exist (or is not visible to the caller). */
    NOT_FOUND(false),

    /** The request conflicts with the resource's current state. */
    CONFLICT(false),

    /** The request is well-formed and a business rule refuses it. */
    RULE_VIOLATION(false),

    /** The caller exceeded a rate or attempt limit. */
    RATE_LIMITED(true),

    /** A dependency answered, and the answer was unusable. */
    UPSTREAM_FAILURE(false),

    /** This service, or a dependency it cannot work without, is temporarily unable to answer. */
    UNAVAILABLE(true),

    /** A fault in this service. Never the caller's doing. */
    INTERNAL(false),
}

/**
 * A stable, machine-readable error code (ADR-0326).
 *
 * The [code] is a published contract exactly as an event type is: once shipped it is never renamed
 * and never reused for a different meaning, because clients branch on it. Implementations are
 * enums — [PlatformErrorCode] for the codes the kernel itself emits, one enum per bounded context
 * for the rest — so the catalogue is closed and enumerable rather than a set of string literals.
 *
 * Framework-free on purpose (ADR-0002): no HTTP status here, see [ErrorCategory].
 */
interface ErrorCode {
    /** The wire spelling; must satisfy [isWellFormed]. */
    val code: String

    val category: ErrorCategory

    /** A short, human-readable, client-safe summary that is the same for every occurrence. */
    val title: String

    /** Whether an identical request may succeed later. Defaults to the category's answer. */
    val retryable: Boolean get() = category.retryableByDefault

    companion object {
        const val MAX_LENGTH = 64

        private val WIRE_FORMAT = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")

        /**
         * `UPPER_SNAKE_CASE`, at most [MAX_LENGTH] characters — the spelling every code already on
         * the wire uses. Also what makes a code safe as a metric label: a value that fails this is
         * not a code, and must never become a label or a response field.
         */
        fun isWellFormed(code: String): Boolean = code.length <= MAX_LENGTH && WIRE_FORMAT.matches(code)
    }
}

/**
 * The kernel's own codes: the ones libs-runtime emits today, under the spelling already on the wire.
 * Domain-specific codes do NOT belong here — they live in an enum owned by their bounded context.
 *
 * Append-only. `PlatformErrorCodeCatalogueTest` holds this enum to a committed baseline, so removing
 * or renaming an entry, or moving it to another category, fails the build.
 */
enum class PlatformErrorCode(
    override val category: ErrorCategory,
    override val title: String,
    private val retryableOverride: Boolean? = null,
) : ErrorCode {
    VALIDATION_ERROR(ErrorCategory.VALIDATION, "The request is not valid"),
    UNAUTHORIZED(ErrorCategory.UNAUTHENTICATED, "Authentication is required"),
    FORBIDDEN(ErrorCategory.FORBIDDEN, "The caller is not allowed to do this"),
    NOT_FOUND(ErrorCategory.NOT_FOUND, "The resource does not exist"),
    CONFLICT(ErrorCategory.CONFLICT, "The request conflicts with the current state"),
    IDEMPOTENCY_KEY_REUSED(ErrorCategory.CONFLICT, "The idempotency key was used for a different request"),

    /** The caller did nothing wrong: the first attempt is still running, so waiting is the fix. */
    IDEMPOTENCY_REQUEST_IN_PROGRESS(
        ErrorCategory.CONFLICT,
        "A request with this idempotency key is still being processed",
        retryableOverride = true,
    ),
    BUSINESS_RULE_VIOLATION(ErrorCategory.RULE_VIOLATION, "A business rule refuses the request"),
    RATE_LIMIT_EXCEEDED(ErrorCategory.RATE_LIMITED, "Too many requests"),
    POLICY_DECISION_POINT_UNAVAILABLE(ErrorCategory.UNAVAILABLE, "Authorization is temporarily unavailable"),
    SERVICE_UNAVAILABLE(ErrorCategory.UNAVAILABLE, "The service is temporarily unavailable"),
    INTERNAL_ERROR(ErrorCategory.INTERNAL, "An unexpected error occurred"),

    /** A monetary amount needs rounding to fit its currency's minor unit (`InvalidMoneyException`). */
    AMOUNT_SCALE_EXCEEDED(ErrorCategory.VALIDATION, "The amount has more decimal places than its currency allows"),

    /** Not an ISO 4217 currency with a minor unit, so it cannot denominate a monetary amount. */
    CURRENCY_UNSUPPORTED(ErrorCategory.VALIDATION, "The currency is not supported"),
    ;

    override val code: String get() = name

    override val retryable: Boolean get() = retryableOverride ?: category.retryableByDefault
}
