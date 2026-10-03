// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.model

import java.time.Instant
import java.util.UUID

enum class FraudInvestigationStatus { OPEN, CLOSED_NO_FINDING }

/** A reviewed investigation root. Its linked score remains a lead, never a finding. */
data class FraudInvestigationCase(
    val id: UUID,
    val scoreId: UUID,
    val accountId: UUID,
    val counterpartyId: UUID?,
    val status: FraudInvestigationStatus,
    val revision: Long,
    val openedBy: String,
    val openedAt: Instant,
    val closedBy: String?,
    val closedAt: Instant?,
) {
    fun closeWithoutFinding(actorId: String, now: Instant): FraudInvestigationCase {
        require(status == FraudInvestigationStatus.OPEN) { "fraud case is already closed" }
        require(actorId.isNotBlank()) { "closing actor is required" }
        return copy(
            status = FraudInvestigationStatus.CLOSED_NO_FINDING,
            revision = revision + 1,
            closedBy = actorId,
            closedAt = now,
        )
    }
}
