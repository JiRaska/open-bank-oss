// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

/** Sanctions and terrorist-list outcome of a customer screen. There is no "unavailable" value: see the port. */
enum class SanctionsScreeningStatus { CLEAR, POTENTIAL_MATCH, MATCH }

data class SanctionsScreeningOutcome(
    val status: SanctionsScreeningStatus,
    val matchScore: Double,
    val matchedName: String?,
)

/**
 * One customer screen answers two different questions, and they must not be merged: is the party
 * on a sanctions or terrorist list ([sanctions]), and is the party politically exposed ([pep]).
 * A PEP is a customer-risk fact that raises the risk tier; it is never a sanctions hit.
 */
data class CustomerScreeningResult(val sanctions: SanctionsScreeningOutcome, val pep: PepScreeningResult)

/**
 * @param subjectId stable identity of the screened party (the party id), which lets
 *   sanctions-service bind an analyst's clearance to this subject across later screens
 * @param organisation true for a legal entity, false for a natural person
 */
data class CustomerScreeningRequest(
    val name: String,
    val subjectId: String,
    val organisation: Boolean,
    val idempotencyKey: String,
)

/** The screen could not be obtained. It is never reported as a result, so it can never pass a check. */
class CustomerScreeningUnavailableException(cause: Throwable) :
    RuntimeException("customer screening (openbank-sanctions-service) is unavailable", cause)

/**
 * Outbound port to `openbank-sanctions-service`'s CUSTOMER screening profile
 * (`POST /api/v3/sanctions/screen`): sanctions lists, the national terrorist list and the PEP
 * list in one call, with the PEP outcome reported apart.
 *
 * @throws CustomerScreeningUnavailableException when no result could be obtained
 */
interface CustomerScreeningPort {
    suspend fun screenCustomer(request: CustomerScreeningRequest): CustomerScreeningResult
}
