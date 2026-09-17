// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.util.UUID

class StatutoryProposalDenied(message: String) : RuntimeException(message)
class StatutoryProposalUnavailable : RuntimeException("statutory representation cannot be verified now")
class StatutoryProposalNotFound(id: UUID) : RuntimeException("statutory proposal $id not found")
class StatutoryProposalStale(id: UUID) : RuntimeException("statutory proposal $id needs a fresh legal rule")

data class StatutoryProposalPage(val operations: List<StatutoryDelegationOperation>, val nextCursor: String?)

/** Creates only inert, immutable proposal evidence; a separate quorum executor may later issue a grant. */
@ApplicationScoped
class StatutoryDelegationProposalService(
    private val rules: StatutoryRuleClient,
    private val validator: DelegationService,
    private val repository: StatutoryDelegationOperationRepository,
    mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val evidence = StatutoryOperationEvidence(mapper)

    @Inject
    constructor(
        rules: StatutoryRuleClient,
        validator: DelegationService,
        repository: StatutoryDelegationOperationRepository,
        mapper: ObjectMapper,
    ) : this(rules, validator, repository, mapper, Clock.systemUTC())

    suspend fun propose(command: PreviewDelegationCommand, requestKey: String): StatutoryOperationCreateOutcome {
        val actor = requireActor(command)
        require(requestKey.isNotBlank() && requestKey.length <= MAX_REQUEST_KEY_LENGTH) {
            "Idempotency-Key is invalid"
        }
        val rule = resolve(command.grantorPartyId, actor)
        validator.validateStatutoryProposal(command)
        val payload = evidence.payload(command)
        val snapshot = evidence.rule(rule)
        val createdAt = clock.instant()
        val outcome = repository.create(
            StatutoryDelegationOperation(
                id = UUID.randomUUID(),
                principalPartyId = command.grantorPartyId,
                initiatorPartyId = actor,
                requestKey = requestKey,
                requestHash = evidence.hash(payload),
                payloadJson = payload,
                policyId = rule.policyId,
                policyRevision = rule.revision,
                sourceCaseId = rule.sourceCaseId,
                ruleHash = evidence.hash(snapshot),
                ruleSnapshotJson = snapshot,
                createdAt = createdAt,
                expiresAt = createdAt.plus(PROPOSAL_LIFETIME),
            ),
        )
        if (outcome is StatutoryOperationCreateOutcome.Replayed &&
            outcome.operation.state == StatutoryOperationState.PENDING &&
            clock.instant() >= outcome.operation.expiresAt
        ) {
            throw StatutoryProposalStale(outcome.operation.id)
        }
        return outcome
    }

    suspend fun get(
        id: UUID,
        principalPartyId: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
    ): StatutoryDelegationOperation = current(id, principalPartyId, callerPartyId, actorPartyId).first

    suspend fun pending(
        principalPartyId: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
    ): List<StatutoryDelegationOperation> {
        val actor = requireActor(principalPartyId, callerPartyId, actorPartyId)
        val rule = resolve(principalPartyId, actor)
        val snapshot = evidence.rule(rule)
        return repository.pending(principalPartyId, evidence.hash(snapshot), clock.instant(), MAX_PENDING_RESULTS)
            .filter { it.ruleSnapshotJson == snapshot }
    }

    suspend fun page(
        principalPartyId: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
        cursor: String?,
        limit: Int?,
    ): StatutoryProposalPage {
        val actor = requireActor(principalPartyId, callerPartyId, actorPartyId)
        val rule = resolve(principalPartyId, actor)
        val snapshot = evidence.rule(rule)
        val hash = evidence.hash(snapshot)
        val pageSize = limit ?: DEFAULT_PAGE_SIZE
        require(pageSize in 1..MAX_PENDING_RESULTS) { "limit must be between 1 and $MAX_PENDING_RESULTS" }
        val position = cursor?.let { StatutoryInboxCursor.decode(it, hash) }
        val fetched = repository.pending(
            principalPartyId,
            hash,
            clock.instant(),
            pageSize + 1,
            position?.first,
            position?.second,
        )
        val visible = fetched.take(pageSize).filter { it.ruleSnapshotJson == snapshot }
        val next = if (fetched.size > pageSize) fetched[pageSize - 1] else null
        return StatutoryProposalPage(visible, next?.let { StatutoryInboxCursor.encode(hash, it) })
    }

    suspend fun decisions(
        id: UUID,
        principalPartyId: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
    ): List<StatutoryDelegationDecision> {
        current(id, principalPartyId, callerPartyId, actorPartyId)
        return repository.decisions(id)
    }

    internal suspend fun current(
        id: UUID,
        principalPartyId: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
    ): Pair<StatutoryDelegationOperation, StatutoryRepresentationRule> {
        val actor = requireActor(principalPartyId, callerPartyId, actorPartyId)
        val operation = repository.find(id, principalPartyId)
            ?.takeIf { it.operationKind == StatutoryOperationKind.ISSUE }
            ?: throw StatutoryProposalNotFound(id)
        val currentRule = resolve(principalPartyId, actor)
        if (operation.state == StatutoryOperationState.PENDING &&
            (clock.instant() >= operation.expiresAt || evidence.rule(currentRule) != operation.ruleSnapshotJson)
        ) {
            throw StatutoryProposalStale(id)
        }
        return operation to currentRule
    }

    private fun requireActor(command: PreviewDelegationCommand): UUID =
        requireActor(command.grantorPartyId, command.callerPartyId, command.actorPartyId)

    private fun requireActor(principal: UUID, caller: UUID?, actor: UUID?): UUID {
        if (caller != principal || actor == null) {
            throw StatutoryProposalDenied("authenticated company profile and human actor are required")
        }
        return actor
    }

    private suspend fun resolve(principal: UUID, actor: UUID) = when (val resolved = rules.resolve(principal, actor)) {
        is StatutoryRuleResolution.RosterMatched -> resolved.rule
        StatutoryRuleResolution.Denied -> throw StatutoryProposalDenied("actor has no current joint representation")
        StatutoryRuleResolution.Unverifiable -> throw StatutoryProposalUnavailable()
    }

    private companion object {
        const val MAX_REQUEST_KEY_LENGTH = 200
        const val MAX_PENDING_RESULTS = 50
        const val DEFAULT_PAGE_SIZE = 20
        val PROPOSAL_LIFETIME: Duration = Duration.ofHours(24)
    }
}
