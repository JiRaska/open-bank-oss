// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.rest.dto

import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import java.time.Instant

/**
 * A VoP request. Field names match what the admin-UI payments form already holds.
 *
 * Validated explicitly in [validated] rather than with bean-validation annotations — the fleet
 * carries no `hibernate-validator` dependency and validates in code.
 */
data class VerifyPayeeRequest(val creditorIban: String? = null, val creditorName: String? = null) {
    /**
     * Check presence and bounds, and return a non-null view. IBAN *structure* is not checked here:
     * `Iban.of` in the use case owns that, so there is one definition of a valid IBAN.
     */
    fun validated(): ValidVerifyPayeeRequest {
        val iban = creditorIban?.trim()
        val name = creditorName?.trim()
        require(!iban.isNullOrBlank()) { "creditorIban is required" }
        require(!name.isNullOrBlank()) { "creditorName is required" }
        require(iban.length <= MAX_IBAN_LENGTH) { "creditorIban is at most $MAX_IBAN_LENGTH characters" }
        require(name.length <= MAX_SEPA_NAME_LENGTH) { "creditorName is at most $MAX_SEPA_NAME_LENGTH characters" }
        return ValidVerifyPayeeRequest(iban, name)
    }

    private companion object {
        const val MAX_IBAN_LENGTH = 34
        const val MAX_SEPA_NAME_LENGTH = 140
    }
}

/** A [VerifyPayeeRequest] whose required fields are known present. */
data class ValidVerifyPayeeRequest(val creditorIban: String, val creditorName: String)

/**
 * A VoP response.
 *
 * [status] serialises to the EPC/admin-UI wire values `match` / `close_match` / `no_match` /
 * `no_data`. [matchedName] is present ONLY for `close_match` — never for `no_match`, which would
 * turn this endpoint into an account-holder-name disclosure oracle (ADR-0171 §6).
 */
data class VerifyPayeeResponse(
    val status: String,
    val matchedName: String? = null,
    val reason: String? = null,
    val verifiedAt: Instant,
) {
    companion object {
        fun of(verification: VopVerification) = VerifyPayeeResponse(
            status = wireValue(verification.outcome),
            matchedName = verification.matchedName,
            reason = verification.noDataReason?.let(::wireReason),
            verifiedAt = verification.verifiedAt,
        )

        private fun wireValue(outcome: VopOutcome): String = when (outcome) {
            VopOutcome.MATCH -> "match"
            VopOutcome.CLOSE_MATCH -> "close_match"
            VopOutcome.NO_MATCH -> "no_match"
            VopOutcome.NO_DATA -> "no_data"
        }

        private fun wireReason(reason: VopNoDataReason): String = when (reason) {
            VopNoDataReason.NO_SCHEME_CONNECTIVITY -> "no_scheme_connectivity"
            VopNoDataReason.ACCOUNT_NOT_FOUND -> "account_not_found"
            VopNoDataReason.NAME_NOT_AVAILABLE -> "name_not_available"
            VopNoDataReason.LOOKUP_UNAVAILABLE -> "lookup_unavailable"
        }
    }
}
