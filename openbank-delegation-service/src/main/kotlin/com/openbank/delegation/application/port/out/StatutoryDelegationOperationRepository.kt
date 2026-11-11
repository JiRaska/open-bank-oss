// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.StatutoryDelegationAction
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.libs.domain.event.DomainEvent
import java.util.UUID

data class StatutoryDelegationCommit(val grant: DelegationGrant, val event: DomainEvent)

data class StatutoryDelegationDecision(
    val operation: StatutoryDelegationOperation,
    val commit: StatutoryDelegationCommit? = null,
)

interface StatutoryDelegationOperationRepository {
    /** Returns the persisted row for either first creation or a retry under the same scoped request key. */
    suspend fun create(requestKey: String, operation: StatutoryDelegationOperation): StatutoryDelegationOperation

    suspend fun find(id: UUID): StatutoryDelegationOperation?

    suspend fun findByRequest(
        principalPartyId: UUID,
        actorPartyId: UUID,
        action: StatutoryDelegationAction,
        requestKey: String,
    ): StatutoryDelegationOperation?

    suspend fun pending(principalPartyId: UUID, actorPartyId: UUID, limit: Int): List<StatutoryDelegationOperation>

    /** Locks the operation and grant and persists the decision, optional grant and outbox in one transaction. */
    suspend fun decideAtomically(
        id: UUID,
        decide: (StatutoryDelegationOperation, DelegationGrant?) -> StatutoryDelegationDecision,
    ): StatutoryDelegationOperation?
}
