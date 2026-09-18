// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import java.time.Instant
import java.util.UUID

sealed interface StatutoryOperationCreateOutcome {
    val operation: StatutoryDelegationOperation

    data class Created(override val operation: StatutoryDelegationOperation) : StatutoryOperationCreateOutcome

    data class Replayed(override val operation: StatutoryDelegationOperation) : StatutoryOperationCreateOutcome
}

class StatutoryOperationCreateConflict : RuntimeException("request key belongs to different statutory evidence")
class StatutoryDecisionConflict : RuntimeException("representative already decided differently")
class StatutoryDecisionClosed : RuntimeException("statutory proposal is no longer pending")
class StatutoryQuorumIncomplete : RuntimeException("statutory approval quorum is incomplete")

interface StatutoryDelegationOperationRepository {
    /** Exact replay returns the existing operation id; changed evidence under one request key fails. */
    suspend fun create(operation: StatutoryDelegationOperation): StatutoryOperationCreateOutcome

    /** Principal scope is mandatory so a guessed operation id never reveals another company. */
    suspend fun find(id: UUID, principalPartyId: UUID): StatutoryDelegationOperation?

    /** Bounded company-scoped pending inbox; never enumerate another principal's evidence. */
    suspend fun pending(
        principalPartyId: UUID,
        ruleHash: String,
        after: Instant,
        limit: Int,
        beforeCreatedAt: Instant? = null,
        beforeId: UUID? = null,
        kind: StatutoryOperationKind = StatutoryOperationKind.ISSUE,
    ): List<StatutoryDelegationOperation>

    /** Decisions belong to an operation already principal-scoped and roster-checked by the caller. */
    suspend fun decisions(operationId: UUID): List<StatutoryDelegationDecision>

    /** Insert only while PENDING and unexpired; exact same-actor retry returns original evidence. */
    suspend fun recordDecision(decision: StatutoryDelegationDecision): StatutoryDelegationDecision

    suspend fun findDecision(operationId: UUID, actorPartyId: UUID): StatutoryDelegationDecision?

    /** Initiator-only cancellation serializes with decisions and execution on the operation row. */
    suspend fun cancel(
        operationId: UUID,
        principalPartyId: UUID,
        initiatorPartyId: UUID,
        kind: StatutoryOperationKind,
        at: Instant,
    ): StatutoryDelegationOperation

    /** Lock operation, check signed quorum, then commit grant + outbox + EXECUTED as one SQL transaction. */
    suspend fun execute(
        operationId: UUID,
        principalPartyId: UUID,
        rule: StatutoryRepresentationRule,
        expectedRuleHash: String,
        grant: DelegationGrant,
        event: DelegationOffered,
        at: Instant,
    ): DelegationGrant

    /** Lock operation and target grant; activation, proof link, event and EXECUTED commit together. */
    suspend fun executeAcceptance(
        operationId: UUID,
        principalPartyId: UUID,
        rule: StatutoryRepresentationRule,
        expectedRuleHash: String,
        expectedPayloadJson: String,
        offered: DelegationGrant,
        event: DelegationActivated,
        at: Instant,
    ): DelegationGrant
}
