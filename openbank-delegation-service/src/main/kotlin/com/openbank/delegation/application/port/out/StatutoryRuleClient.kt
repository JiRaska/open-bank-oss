// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import java.util.UUID

sealed interface StatutoryRuleResolution {
    /** Party roster and live KYB attestation match now; execution must recheck them. */
    data class RosterMatched(val rule: StatutoryRepresentationRule) : StatutoryRuleResolution
    data object Denied : StatutoryRuleResolution
    data object Unverifiable : StatutoryRuleResolution
}

/** Reads signed Party policy, current same-case mandates and live KYB attestation; not issuance authorization. */
interface StatutoryRuleClient {
    suspend fun resolve(principalPartyId: UUID, actorPartyId: UUID): StatutoryRuleResolution
}
