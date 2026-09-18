// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** Versioned evidence of cancelling an inert JOINT operation, never a grant lifecycle event. */
data class StatutoryDelegationProposalCancelled(
    override val aggregateId: UUID,
    val principalPartyId: UUID,
    val actorId: UUID,
    val operationKind: StatutoryOperationKind,
    val requestHash: String,
    val ruleHash: String,
    val targetGrantId: UUID?,
    override val occurredAt: Instant,
    // Also stamped by the shared publisher; explicit here keeps the wire contract/code ratchet exact.
    val sourceService: String = "delegation-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "StatutoryDelegationOperation"
    override val eventType = "StatutoryDelegationProposalCancelled"
    override val version = 1L
}
