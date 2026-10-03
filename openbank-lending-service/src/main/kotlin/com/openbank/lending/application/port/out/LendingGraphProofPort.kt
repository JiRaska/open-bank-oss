// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import java.util.UUID

/** Boolean-only checks against the source owners. An outage must propagate, never become a match. */
interface LendingGraphProofPort {
    suspend fun hasVerifiedGuarantorIdentity(partyId: UUID): Boolean

    suspend fun matchesSignedGuarantee(
        documentId: UUID,
        loanId: UUID,
        guarantorPartyId: UUID,
        bankScope: String,
        sealedSha256: String,
    ): Boolean
}

/** A proof owner could not give a decision. Never turn this into a negative or verified fact. */
class LendingGraphProofUnavailable(cause: Throwable) : RuntimeException("Lending graph source proof unavailable", cause)
