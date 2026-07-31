// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.`in`

import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.Exposure
import com.openbank.libs.domain.money.Money
import java.time.OffsetDateTime
import java.util.UUID

data class OfferDelegationCommand(
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    val requiredApprovals: Int? = null,
    val perTransactionLimit: Money? = null,
    val dailyLimit: Money? = null,
    val monthlyLimit: Money? = null,
    val exposure: Exposure? = null,
    val validTo: OffsetDateTime?,
    val grantScaSessionId: UUID,
    val note: String? = null,
)

data class RevokeDelegationCommand(val delegationId: UUID, val revokedBy: UUID, val reason: String)

data class SuspendDelegationCommand(val delegationId: UUID, val reason: String)

data class CheckDelegationCommand(
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capability: DelegationCapability,
    val amount: Money? = null,
)

interface OfferDelegationUseCase {
    suspend fun offer(command: OfferDelegationCommand): DelegationGrant
}

interface RespondDelegationUseCase {
    suspend fun accept(delegationId: UUID, granteePartyId: UUID, scaSessionId: UUID): DelegationGrant
    suspend fun decline(delegationId: UUID, granteePartyId: UUID): DelegationGrant
    suspend fun renounce(delegationId: UUID, granteePartyId: UUID): DelegationGrant
}

interface RevokeDelegationUseCase {
    suspend fun revoke(command: RevokeDelegationCommand): DelegationGrant
    suspend fun suspend(command: SuspendDelegationCommand): DelegationGrant
    suspend fun reinstate(delegationId: UUID): DelegationGrant
}

interface GetDelegationUseCase {
    suspend fun getDelegation(delegationId: UUID): DelegationGrant
    suspend fun listByGrantor(grantorPartyId: UUID): List<DelegationGrant>
    suspend fun listByGrantee(granteePartyId: UUID): List<DelegationGrant>
}

/**
 * The ADR-0232 D3 question, keyed by what the enforcing service holds. Returns a
 * decision, not the grant, for the same reason consent-service's hasActiveConsent
 * returns a boolean: a yes/no answer cannot be cached into an authorization copy.
 * Product services with their own event-fed projection never call this — it exists
 * for services that have not built the projection yet and for reconciliation.
 */
interface CheckDelegationUseCase {
    suspend fun check(command: CheckDelegationCommand): DelegationCheckResult
}
