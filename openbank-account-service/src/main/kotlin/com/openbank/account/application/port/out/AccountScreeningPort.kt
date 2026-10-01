// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

/** Raised when the sanctions service cannot be reached — the gate fails closed (ADR-0032 §C). */
class AccountScreeningUnavailableException(cause: Throwable) :
    RuntimeException("Sanctions screening unavailable; account opening blocked", cause)

/**
 * Result of a sanctions name screen. [status] is sanctions-service's own vocabulary, passed through
 * verbatim: `CLEAR | HIT | POTENTIAL_HIT | WHITELISTED | ESCALATED` (its `openapi.yaml`), or
 * [UNKNOWN] when the response carried no status at all.
 */
data class SanctionsScreenResult(val status: String, val matchScore: Double, val matchedName: String?) {
    /**
     * Whether the screen allows an account to open. An ALLOW-list, never a deny-list: the gate used
     * to block `HIT` and `REVIEW`, and sanctions-service has never returned `REVIEW`, so a
     * `POTENTIAL_HIT` (a 0.65+ fuzzy match), an `ESCALATED` case and a response with no status all
     * opened the account. Anything this service does not positively recognise as clean blocks.
     */
    val permitsOpening: Boolean get() = status in PERMITTING_STATUSES

    companion object {
        /** A response that carried no status. Never permits opening. */
        const val UNKNOWN = "UNKNOWN"

        /** CLEAR: no match. WHITELISTED: a match compliance has already cleared for this party. */
        val PERMITTING_STATUSES: Set<String> = setOf("CLEAR", "WHITELISTED")
    }
}

/**
 * Outbound port to the sanctions service synchronous screen endpoint.
 * Fails closed: if the service is unreachable, [AccountScreeningUnavailableException] is thrown
 * and the account MUST NOT be opened (ADR-0032 §C).
 */
interface AccountSanctionsScreeningPort {
    /** Screen [name] against global sanctions lists. [idempotencyKey] deduplicates concurrent opens. */
    suspend fun screen(name: String, idempotencyKey: String): SanctionsScreenResult
}
