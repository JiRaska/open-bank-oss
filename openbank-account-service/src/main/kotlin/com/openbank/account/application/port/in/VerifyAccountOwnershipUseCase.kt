// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.`in`

import com.openbank.account.domain.model.OwnershipVerdict
import java.util.UUID

data class VerifyAccountOwnershipQuery(val iban: String, val partyId: UUID)

/** ADR-0335 D2: answer "does this party own this IBAN, and is it active" — and nothing more. */
interface VerifyAccountOwnershipUseCase {
    suspend fun verifyOwnership(query: VerifyAccountOwnershipQuery): OwnershipVerdict
}
