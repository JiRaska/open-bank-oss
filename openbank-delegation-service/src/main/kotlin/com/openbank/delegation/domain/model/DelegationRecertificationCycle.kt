// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.time.OffsetDateTime
import java.util.UUID

enum class DelegationRecertificationStatus { PENDING, CONFIRMED }

/**
 * Immutable evidence for one periodic review opportunity. A cycle never changes grant authority:
 * confirmation means only that the grantor explicitly reviewed the exact active lifecycle
 * revision. Revoke and narrowing remain separate, authority-changing customer actions.
 */
data class DelegationRecertificationCycle(
    val id: UUID = Ids.newId(),
    val delegationId: UUID,
    val grantorPartyId: UUID,
    val expectedLifecycleRevision: Long,
    val audience: DelegationRecertificationAudience,
    val sequence: Int,
    val dueAt: OffsetDateTime,
    val status: DelegationRecertificationStatus = DelegationRecertificationStatus.PENDING,
    val createdAt: OffsetDateTime,
    val confirmedAt: OffsetDateTime? = null,
    val confirmedBy: UUID? = null,
) {
    init {
        require(expectedLifecycleRevision >= 0) { "expected lifecycle revision must not be negative" }
        require(sequence > 0) { "recertification sequence must be positive" }
        require(
            (status == DelegationRecertificationStatus.PENDING && confirmedAt == null && confirmedBy == null) ||
                (status == DelegationRecertificationStatus.CONFIRMED && confirmedAt != null && confirmedBy != null),
        ) { "recertification confirmation evidence must match its state" }
    }

    fun confirm(callerPartyId: UUID, now: OffsetDateTime): DelegationRecertificationCycle {
        check(status == DelegationRecertificationStatus.PENDING) { "recertification cycle is already confirmed" }
        check(callerPartyId == grantorPartyId) { "only the grantor may confirm a recertification" }
        return copy(
            status = DelegationRecertificationStatus.CONFIRMED,
            confirmedAt = now,
            confirmedBy = callerPartyId,
        )
    }
}
