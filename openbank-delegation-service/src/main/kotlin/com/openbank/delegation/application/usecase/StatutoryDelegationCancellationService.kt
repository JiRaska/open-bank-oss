// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.event.StatutoryDelegationProposalCancelled
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.util.UUID

/** Cancels only the initiator's inert proposal; decisions remain immutable evidence. */
@ApplicationScoped
class StatutoryDelegationCancellationService(
    private val rules: StatutoryRuleClient,
    private val operations: StatutoryDelegationOperationRepository,
    private val clock: Clock,
) {
    @Inject
    constructor(rules: StatutoryRuleClient, operations: StatutoryDelegationOperationRepository) :
        this(rules, operations, Clock.systemUTC())

    @Suppress("ThrowsCount") // Distinct denial states intentionally retain distinct HTTP mappings.
    suspend fun cancel(
        id: UUID,
        company: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
        kind: StatutoryOperationKind,
    ): StatutoryDelegationOperation {
        val actor = actorPartyId?.takeIf { callerPartyId == company }
            ?: throw StatutoryProposalDenied("authenticated company profile and human actor are required")
        val operation = operations.find(id, company)?.takeIf { it.operationKind == kind }
            ?: throw StatutoryProposalNotFound(id)
        if (operation.initiatorPartyId != actor) {
            throw StatutoryProposalDenied("only the proposal initiator may cancel it")
        }
        // A changed rule invalidates signing, but must not trap a still-authorised initiator's
        // proposal. Check current roster membership without rewriting the frozen rule evidence.
        when (val resolution = rules.resolve(company, actor)) {
            is StatutoryRuleResolution.RosterMatched -> {
                if (resolution.rule.principalPartyId != company ||
                    resolution.rule.eligibleRepresentatives.none { it.partyId == actor }
                ) {
                    throw StatutoryProposalDenied("actor has no current joint representation")
                }
            }
            StatutoryRuleResolution.Denied ->
                throw StatutoryProposalDenied("actor has no current joint representation")
            StatutoryRuleResolution.Unverifiable -> throw StatutoryProposalUnavailable()
        }
        val at = clock.instant()
        return operations.cancel(
            id,
            company,
            actor,
            kind,
            at,
            StatutoryDelegationProposalCancelled(
                aggregateId = operation.id,
                principalPartyId = company,
                actorId = actor,
                operationKind = kind,
                requestHash = operation.requestHash,
                ruleHash = operation.ruleHash,
                targetGrantId = operation.targetGrantId,
                occurredAt = at,
            ),
        )
    }
}
