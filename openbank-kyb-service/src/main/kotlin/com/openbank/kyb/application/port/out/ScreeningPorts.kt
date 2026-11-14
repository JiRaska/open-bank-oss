// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.out

import com.openbank.kyb.domain.model.ScreeningVerdict

/**
 * @param subjectId stable identity of the screened subject, which lets sanctions-service bind an
 *   analyst's clearance to this subject across later screens
 * @param organisation true for a legal person, false for a natural person
 * @param idempotencyKey one per (case, subject), so a retried screen replays instead of repeating
 */
data class RelatedPartyScreeningRequest(
    val name: String,
    val subjectId: String,
    val organisation: Boolean,
    val idempotencyKey: String,
)

/**
 * One screen answers two questions that must not be merged: is the subject on a sanctions or
 * terrorist list ([sanctions], never [ScreeningVerdict.PENDING] here), and is the subject
 * politically exposed ([pep]).
 */
data class RelatedPartyScreeningResult(val sanctions: ScreeningVerdict, val pep: Boolean, val matchedName: String?)

/** The screen could not be obtained. It is never reported as a result, so it can never pass a check. */
class RelatedPartyScreeningUnavailableException(cause: Throwable) :
    RuntimeException("related-party screening (openbank-sanctions-service) is unavailable", cause)

/**
 * Outbound port to `openbank-sanctions-service`'s CUSTOMER screening profile
 * (`POST /api/v3/sanctions/screen`): sanctions lists, the national terrorist list and the PEP list
 * in one call, with the PEP outcome reported apart.
 *
 * @throws RelatedPartyScreeningUnavailableException when no result could be obtained
 */
interface RelatedPartyScreeningPort {
    suspend fun screen(request: RelatedPartyScreeningRequest): RelatedPartyScreeningResult
}
