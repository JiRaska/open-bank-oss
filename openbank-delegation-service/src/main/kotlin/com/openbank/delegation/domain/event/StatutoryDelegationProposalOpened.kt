// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** A hint to the frozen human roster; the live mandate is rechecked before any signature. */
data class StatutoryDelegationProposalOpened(
    override val aggregateId: UUID,
    val principalPartyId: UUID,
    val actorId: UUID,
    val operationKind: StatutoryOperationKind,
    val representativePartyIds: List<UUID>,
    val requestHash: String,
    val ruleHash: String,
    val expiresAt: Instant,
    override val occurredAt: Instant,
    val sourceService: String = "delegation-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "StatutoryDelegationOperation"
    override val eventType = "StatutoryDelegationProposalOpened"
    override val version = 1L
}
