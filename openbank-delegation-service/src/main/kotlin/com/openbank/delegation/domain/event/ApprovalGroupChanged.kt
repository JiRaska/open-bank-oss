// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.ApprovalGroup
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

data class ApprovalGroupChanged(
    override val aggregateId: UUID,
    val ownerPartyId: UUID,
    val name: String,
    val members: Set<UUID>,
    val threshold: Int,
    val revision: Long,
    val active: Boolean,
    val scaSessionId: UUID?,
    override val eventType: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "ApprovalGroup"
    override val version = 1L
    companion object {
        fun from(group: ApprovalGroup, eventType: String, at: Instant) = ApprovalGroupChanged(
            aggregateId = group.id,
            ownerPartyId = group.ownerPartyId,
            name = group.name,
            members = group.members,
            threshold = group.threshold,
            revision = group.revision,
            active = group.active,
            scaSessionId = group.lastScaSessionId.takeIf { group.active },
            eventType = eventType,
            occurredAt = at,
        )
    }
}
