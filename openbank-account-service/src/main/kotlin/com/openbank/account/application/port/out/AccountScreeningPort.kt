// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.application.port.out

/** Raised when the sanctions service cannot be reached — the gate fails closed (ADR-0032 §C). */
class AccountScreeningUnavailableException(cause: Throwable) :
    RuntimeException("Sanctions screening unavailable; account opening blocked", cause)

/** Result of a sanctions name screen. */
data class SanctionsScreenResult(
    val status: String, // "CLEAR" | "HIT" | "REVIEW"
    val matchScore: Double,
    val matchedName: String?,
)

/**
 * Outbound port to the sanctions service synchronous screen endpoint.
 * Fails closed: if the service is unreachable, [AccountScreeningUnavailableException] is thrown
 * and the account MUST NOT be opened (ADR-0032 §C).
 */
interface AccountSanctionsScreeningPort {
    /** Screen [name] against global sanctions lists. [idempotencyKey] deduplicates concurrent opens. */
    suspend fun screen(name: String, idempotencyKey: String): SanctionsScreenResult
}
