// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.time.OffsetDateTime
import java.util.UUID

/** Owner-managed, versioned roster from which an operation may later take an immutable snapshot. */
data class ApprovalGroup(
    val id: UUID = Ids.newId(),
    val ownerPartyId: UUID,
    val name: String,
    val members: Set<UUID>,
    val threshold: Int,
    val revision: Long = 1,
    val active: Boolean = true,
    val lastScaSessionId: UUID,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(name.isNotBlank()) { "approval group name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "approval group name must not exceed $MAX_NAME_LENGTH characters" }
        require(members.isNotEmpty()) { "approval group must contain at least one member" }
        require(members.size <= MAX_MEMBERS) { "approval group must not exceed $MAX_MEMBERS members" }
        require(ownerPartyId !in members) { "approval group owner must not also be a member" }
        require(threshold in 1..members.size) { "approval group threshold must be between 1 and member count" }
        require(revision >= 1) { "approval group revision must be positive" }
    }

    fun revise(
        newName: String,
        newMembers: Set<UUID>,
        newThreshold: Int,
        scaSessionId: UUID,
        now: OffsetDateTime,
    ): ApprovalGroup {
        check(active) { "inactive approval group cannot be revised" }
        return copy(
            name = newName.trim(),
            members = newMembers,
            threshold = newThreshold,
            revision = revision + 1,
            lastScaSessionId = scaSessionId,
            updatedAt = now,
        )
    }

    fun deactivate(now: OffsetDateTime): ApprovalGroup {
        check(active) { "approval group is already inactive" }
        return copy(active = false, revision = revision + 1, updatedAt = now)
    }

    companion object {
        const val MAX_NAME_LENGTH = 120
        const val MAX_MEMBERS = 50
    }
}
