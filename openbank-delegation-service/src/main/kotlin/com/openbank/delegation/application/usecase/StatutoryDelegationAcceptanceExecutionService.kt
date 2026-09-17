// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.EventMoney
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryOperationState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** The repository rechecks quorum and the locked offer before atomically activating it. */
@ApplicationScoped
class StatutoryDelegationAcceptanceExecutionService(
    private val proposals: StatutoryDelegationAcceptanceProposalService,
    private val grants: DelegationRepository,
    private val operations: StatutoryDelegationOperationRepository,
    mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val evidence = StatutoryOperationEvidence(mapper)

    @Inject
    constructor(
        proposals: StatutoryDelegationAcceptanceProposalService,
        grants: DelegationRepository,
        operations: StatutoryDelegationOperationRepository,
        mapper: ObjectMapper,
    ) : this(proposals, grants, operations, mapper, Clock.systemUTC())

    suspend fun execute(id: UUID, company: UUID, actor: UUID): DelegationGrant {
        val (operation, rule) = proposals.current(id, company, company, actor)
        if (operation.state == StatutoryOperationState.EXECUTED) {
            return existingGrant(id, company, requireNotNull(operation.grantId))
        }
        if (operation.state != StatutoryOperationState.PENDING) throw StatutoryProposalStale(id)
        val offered = pendingOffer(id, company, requireNotNull(operation.targetGrantId))
        val at = clock.instant()
        val activated = offered.acceptJoint(id, at.atOffset(ZoneOffset.UTC))
        val event = DelegationActivated(
            aggregateId = activated.id,
            lifecycleRevision = activated.lifecycleRevision,
            grantorPartyId = activated.grantorPartyId,
            granteePartyId = activated.granteePartyId,
            resourceType = activated.resourceType,
            resourceId = activated.resourceId,
            capabilities = activated.capabilities,
            approvalPolicy = activated.approvalPolicy,
            requiredApprovals = activated.requiredApprovals,
            validFrom = activated.validFrom,
            validTo = activated.validTo,
            perTransactionLimit = EventMoney.from(activated.perTransactionLimit),
            occurredAt = at,
        )
        val ruleHash = evidence.hash(evidence.rule(rule))
        return operations.executeAcceptance(
            id,
            company,
            rule,
            ruleHash,
            evidence.acceptance(offered),
            offered,
            event,
            at,
        )
    }

    private suspend fun existingGrant(operationId: UUID, company: UUID, grantId: UUID): DelegationGrant =
        grants.findById(grantId)
            ?.takeIf { it.granteePartyId == company && it.acceptStatutoryOperationId == operationId }
            ?: throw StatutoryProposalStale(operationId)

    private suspend fun pendingOffer(operationId: UUID, company: UUID, grantId: UUID): DelegationGrant =
        grants.findById(grantId)
            ?.takeIf { it.granteePartyId == company && it.status == DelegationStatus.OFFERED }
            ?: throw StatutoryProposalStale(operationId)
}
