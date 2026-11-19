// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.StatutoryDelegationRule
import java.util.UUID

interface StatutoryDelegationRuleReader {
    /** The exact live Party snapshot corroborated by the current KYB attestation. */
    suspend fun current(principalPartyId: UUID): StatutoryDelegationRule?
}
