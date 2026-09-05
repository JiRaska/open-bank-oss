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

/**
 * The party the customer channel authenticated, as forwarded by customer-edge in
 * `X-Customer-Party-Id` (the fleet's IDOR-guard convention — AccountResource.CUSTOMER_PARTY_HEADER).
 *
 * `null` means the call did NOT arrive on a customer-scoped path: an operator console or a
 * back-office service. Those are gated by role and by OPA, not by this value.
 *
 * Every customer-facing operation takes one, because before it existed the acting party was
 * whatever the request body said. A grantee id in a body is a claim; this is the only thing in
 * the request the caller cannot choose.
 */
typealias CallerPartyId = UUID?

interface DelegationCandidate {
    val callerPartyId: CallerPartyId
    val grantorPartyId: UUID
    val granteePartyId: UUID
    val resourceType: DelegationResourceType
    val resourceId: UUID
    val capabilities: Set<DelegationCapability>
    val approvalPolicy: ApprovalPolicy
    val requiredApprovals: Int?
    val perTransactionLimit: Money?
    val dailyLimit: Money?
    val monthlyLimit: Money?
    val exposure: Exposure?
    val validTo: OffsetDateTime?
}

data class PreviewDelegationCommand(
    override val callerPartyId: CallerPartyId,
    override val grantorPartyId: UUID,
    override val granteePartyId: UUID,
    override val resourceType: DelegationResourceType,
    override val resourceId: UUID,
    override val capabilities: Set<DelegationCapability>,
    override val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    override val requiredApprovals: Int? = null,
    override val perTransactionLimit: Money? = null,
    override val dailyLimit: Money? = null,
    override val monthlyLimit: Money? = null,
    override val exposure: Exposure? = null,
    override val validTo: OffsetDateTime?,
) : DelegationCandidate

data class OfferDelegationCommand(
    override val callerPartyId: CallerPartyId,
    override val grantorPartyId: UUID,
    override val granteePartyId: UUID,
    override val resourceType: DelegationResourceType,
    override val resourceId: UUID,
    override val capabilities: Set<DelegationCapability>,
    override val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    override val requiredApprovals: Int? = null,
    override val perTransactionLimit: Money? = null,
    override val dailyLimit: Money? = null,
    override val monthlyLimit: Money? = null,
    override val exposure: Exposure? = null,
    override val validTo: OffsetDateTime?,
    val grantScaSessionId: UUID,
    val note: String? = null,
) : DelegationCandidate

/**
 * ADR-0232 D4. [revokedBy] is now derived from the authenticated caller, never from the request:
 * as a client-supplied query parameter it both authorised the act and wrote the audit field
 * `closedBy`, so any caller could revoke any grant AND name someone else as having done it.
 *
 * [bankInitiated] is true only for an operator/back-office call (role-gated at the REST layer).
 * A customer revoke must be the grantor's own.
 */
data class RevokeDelegationCommand(
    val delegationId: UUID,
    val revokedBy: UUID,
    val reason: String,
    val bankInitiated: Boolean = false,
)

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

interface PreviewDelegationUseCase {
    /** Validates the complete draft without consuming SCA or creating authority. */
    suspend fun preview(command: PreviewDelegationCommand)
}

interface RespondDelegationUseCase {
    suspend fun accept(
        delegationId: UUID,
        granteePartyId: UUID,
        scaSessionId: UUID,
        callerPartyId: CallerPartyId,
    ): DelegationGrant

    suspend fun decline(delegationId: UUID, granteePartyId: UUID, callerPartyId: CallerPartyId): DelegationGrant
    suspend fun renounce(delegationId: UUID, granteePartyId: UUID, callerPartyId: CallerPartyId): DelegationGrant
}

interface RevokeDelegationUseCase {
    suspend fun revoke(command: RevokeDelegationCommand): DelegationGrant
    suspend fun suspend(command: SuspendDelegationCommand): DelegationGrant
    suspend fun reinstate(delegationId: UUID): DelegationGrant
}

/**
 * Reads are party-scoped for the same reason the writes are: a delegation grant names two people,
 * one resource and a set of money capabilities, and the whole set was readable by grant id (and
 * enumerable by party id) for any authenticated caller. A customer-scoped caller may only see a
 * grant they are a party to.
 */
interface GetDelegationUseCase {
    suspend fun getDelegation(delegationId: UUID, callerPartyId: CallerPartyId): DelegationGrant
    suspend fun listByGrantor(grantorPartyId: UUID, callerPartyId: CallerPartyId): List<DelegationGrant>
    suspend fun listByGrantee(granteePartyId: UUID, callerPartyId: CallerPartyId): List<DelegationGrant>
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
