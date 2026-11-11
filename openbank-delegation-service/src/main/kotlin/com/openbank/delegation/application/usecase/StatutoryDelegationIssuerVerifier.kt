// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationRuleReader
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.StatutoryDelegationAction
import com.openbank.delegation.domain.model.StatutoryDelegationStatus
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StatutoryDelegationIssuerVerifier(
    private val operations: StatutoryDelegationOperationRepository,
    private val rules: StatutoryDelegationRuleReader,
) {
    suspend fun requireCurrent(grant: DelegationGrant) {
        val operationId = requireNotNull(grant.grantorStatutoryOperationId)
        val operation = operations.find(operationId)
        val issuedForGrant = operation?.action == StatutoryDelegationAction.ISSUE &&
            operation.status == StatutoryDelegationStatus.COMMITTED &&
            operation.delegationId == grant.id
        val samePrincipal = operation?.rule?.principalPartyId == grant.grantorPartyId
        if (!issuedForGrant || !samePrincipal || rules.current(grant.grantorPartyId) != operation.rule) {
            throw DelegationGrantorAuthorityException("the joint issuer evidence is missing or no longer current")
        }
    }
}
