// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Creates inert, content-addressed evidence for a joint grantee's acceptance of an existing offer. */
@ApplicationScoped
class StatutoryDelegationAcceptanceProposalService(
    private val grants: DelegationRepository,
    private val rules: StatutoryRuleClient,
    private val operations: StatutoryDelegationOperationRepository,
    mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val evidence = StatutoryOperationEvidence(mapper)

    @Inject
    constructor(
        grants: DelegationRepository,
        rules: StatutoryRuleClient,
        operations: StatutoryDelegationOperationRepository,
        mapper: ObjectMapper,
    ) : this(grants, rules, operations, mapper, Clock.systemUTC())

    suspend fun propose(
        grantId: UUID,
        companyPartyId: UUID,
        callerPartyId: UUID?,
        actorPartyId: UUID?,
        requestKey: String,
    ): StatutoryOperationCreateOutcome {
        val actor = requireActor(companyPartyId, callerPartyId, actorPartyId)
        require(requestKey.isNotBlank() && requestKey.length <= MAX_REQUEST_KEY_LENGTH) {
            "Idempotency-Key is invalid"
        }
        // Hide grants addressed to another party before consulting the legal roster.
        val now = clock.instant()
        val grant = loadOffered(grantId, companyPartyId, now)
        val rule = resolve(companyPartyId, actor)
        val payload = evidence.acceptance(grant)
        val snapshot = evidence.rule(rule)
        val operation = StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = companyPartyId,
            initiatorPartyId = actor,
            requestKey = requestKey,
            requestHash = evidence.hash(payload),
            payloadJson = payload,
            policyId = rule.policyId,
            policyRevision = rule.revision,
            sourceCaseId = rule.sourceCaseId,
            ruleHash = evidence.hash(snapshot),
            ruleSnapshotJson = snapshot,
            createdAt = now,
            expiresAt = now.plus(PROPOSAL_LIFETIME),
            operationKind = StatutoryOperationKind.ACCEPT,
            targetGrantId = grant.id,
            expectedLifecycleRevision = grant.lifecycleRevision,
        )
        val outcome = operations.create(operation)
        ensureFreshReplay(outcome)
        return outcome
    }

    private fun requireActor(company: UUID, caller: UUID?, actor: UUID?): UUID = actor?.takeIf { caller == company }
        ?: throw StatutoryProposalDenied("authenticated company profile and human actor are required")

    private suspend fun loadOffered(grantId: UUID, company: UUID, now: java.time.Instant): DelegationGrant {
        val grant = grants.findById(grantId)?.takeIf { it.granteePartyId == company }
            ?: throw StatutoryProposalNotFound(grantId)
        val live = grant.status == DelegationStatus.OFFERED &&
            grant.exposure == null &&
            (grant.validTo?.toInstant()?.isAfter(now) != false)
        if (!live) throw StatutoryProposalDenied("offer is not eligible for joint acceptance")
        return grant
    }

    private suspend fun resolve(company: UUID, actor: UUID): StatutoryRepresentationRule {
        val rule = when (val resolution = rules.resolve(company, actor)) {
            is StatutoryRuleResolution.RosterMatched -> resolution.rule
            StatutoryRuleResolution.Denied -> throw StatutoryProposalDenied("actor has no current joint representation")
            StatutoryRuleResolution.Unverifiable -> throw StatutoryProposalUnavailable()
        }
        return checkedRoster(rule, company, actor)
    }

    private fun checkedRoster(
        rule: StatutoryRepresentationRule,
        company: UUID,
        actor: UUID,
    ): StatutoryRepresentationRule {
        if (rule.principalPartyId != company || rule.eligibleRepresentatives.none { it.partyId == actor }) {
            throw StatutoryProposalDenied("legal roster does not match this company and actor")
        }
        return rule
    }

    private fun ensureFreshReplay(outcome: StatutoryOperationCreateOutcome) {
        if (outcome is StatutoryOperationCreateOutcome.Replayed &&
            outcome.operation.state == StatutoryOperationState.PENDING &&
            !clock.instant().isBefore(outcome.operation.expiresAt)
        ) {
            throw StatutoryProposalStale(outcome.operation.id)
        }
    }

    private companion object {
        const val MAX_REQUEST_KEY_LENGTH = 200
        val PROPOSAL_LIFETIME: Duration = Duration.ofHours(24)
    }
}
