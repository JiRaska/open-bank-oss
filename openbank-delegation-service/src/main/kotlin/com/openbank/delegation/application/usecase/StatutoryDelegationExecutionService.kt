// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.event.EventMoney
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.StatutoryOperationState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** Recheck the live rule and ordinary grant gates; the repository alone decides quorum under a row lock. */
@ApplicationScoped
class StatutoryDelegationExecutionService(
    private val proposals: StatutoryDelegationProposalService,
    private val validator: DelegationService,
    private val grants: DelegationRepository,
    private val operations: StatutoryDelegationOperationRepository,
    mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val evidence = StatutoryOperationEvidence(mapper)

    @Inject
    constructor(
        proposals: StatutoryDelegationProposalService,
        validator: DelegationService,
        grants: DelegationRepository,
        operations: StatutoryDelegationOperationRepository,
        mapper: ObjectMapper,
    ) : this(proposals, validator, grants, operations, mapper, Clock.systemUTC())

    suspend fun execute(id: UUID, principal: UUID, actor: UUID): DelegationGrant {
        val (operation, currentRule) = proposals.current(id, principal, principal, actor)
        if (operation.state == StatutoryOperationState.EXECUTED) {
            return existingGrant(id, requireNotNull(operation.grantId))
        }
        if (operation.state != StatutoryOperationState.PENDING) throw StatutoryProposalStale(id)
        val ruleHash = evidence.hash(evidence.rule(currentRule))
        val draft = evidence.decode(operation.payloadJson, principal, actor)
        if (ruleHash != operation.ruleHash || evidence.payload(draft) != operation.payloadJson) {
            throw StatutoryProposalStale(id)
        }
        val now = OffsetDateTime.now(clock)
        val grant = validator.buildStatutoryGrant(draft, now)
        val event = DelegationOffered(
            aggregateId = grant.id,
            lifecycleRevision = grant.lifecycleRevision,
            grantorPartyId = grant.grantorPartyId,
            granteePartyId = grant.granteePartyId,
            resourceType = grant.resourceType,
            resourceId = grant.resourceId,
            capabilities = grant.capabilities,
            approvalPolicy = grant.approvalPolicy,
            requiredApprovals = grant.requiredApprovals,
            validFrom = grant.validFrom,
            validTo = grant.validTo,
            perTransactionLimit = EventMoney.from(grant.perTransactionLimit),
            occurredAt = clock.instant(),
        )
        return operations.execute(id, principal, currentRule, ruleHash, grant, event, clock.instant())
    }

    private suspend fun existingGrant(operationId: UUID, grantId: UUID): DelegationGrant =
        grants.findById(grantId) ?: throw StatutoryProposalStale(operationId)
}
