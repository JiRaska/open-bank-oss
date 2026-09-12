// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.domain.model

import java.util.UUID

/** Local, fail-closed authorization projection of a party-service representation mandate. */
data class PartyMandateProjection(
    val id: UUID,
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val authority: String,
    val requiredSignatures: Int?,
    val active: Boolean,
) {
    /** Only an exact sole-representation fact can authorize one actor on the current single-decision path. */
    fun permitsSoleDecision(): Boolean = active && authority == "SOLE" && requiredSignatures == 1
}
