// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import java.time.Instant
import java.util.UUID

enum class StatutoryDecisionVerdict { APPROVE, REJECT }

/** One human's write-once answer. Only APPROVE may carry consumed, device-bound SCA evidence. */
data class StatutoryDelegationDecision(
    val operationId: UUID,
    val actorPartyId: UUID,
    val verdict: StatutoryDecisionVerdict,
    val scaSessionId: UUID?,
    val decidedAt: Instant,
) {
    init {
        require((verdict == StatutoryDecisionVerdict.APPROVE) == (scaSessionId != null)) {
            "statutory approval requires SCA; rejection must not claim one"
        }
    }
}
