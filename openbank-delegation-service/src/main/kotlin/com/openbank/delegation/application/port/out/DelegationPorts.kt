// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.libs.domain.event.DomainEvent
import io.smallrye.mutiny.Uni
import java.time.OffsetDateTime
import java.util.UUID

interface DelegationRepository {
    suspend fun save(grant: DelegationGrant, event: DomainEvent): DelegationGrant
    suspend fun findById(id: UUID): DelegationGrant?
    suspend fun findByGrantorId(grantorPartyId: UUID): List<DelegationGrant>
    suspend fun findByGranteeId(granteePartyId: UUID): List<DelegationGrant>
    suspend fun findActiveByGranteeAndResource(
        granteePartyId: UUID,
        resourceType: DelegationResourceType,
        resourceId: UUID,
    ): List<DelegationGrant>

    fun findExpiredActive(threshold: OffsetDateTime): Uni<List<DelegationGrant>>
    fun markExpired(
        id: UUID,
        expectedLifecycleRevision: Long,
        expiredAt: OffsetDateTime,
        event: DomainEvent,
    ): Uni<Boolean>
}

/**
 * The aggregate changed after the use case read it. Retrying from a fresh read is safe; writing the
 * detached snapshot is not, because it could reopen a grant a concurrent transition already closed.
 */
class DelegationConcurrentTransitionException(id: UUID, expectedRevision: Long) :
    RuntimeException("delegation $id changed after lifecycle revision $expectedRevision")

/**
 * ADR-0232 D5 — what the eligibility gate needs to know about a party, nothing more.
 *
 * [displayName] is the ONE extra field, and it is not an eligibility input: it is the
 * counterparty label snapshotted onto the grant at offer time (issue #3604). It is deliberately
 * a single already-composed string rather than the party's core attributes — the grant record has
 * no use for a birthdate, and the narrower the shape the less PII travels into this service.
 * Null when pid-service returns no usable name; the caller must then fall back to the id.
 */
data class PartyEligibility(
    val partyId: UUID,
    val active: Boolean,
    val kycLevel: String,
    val displayName: String? = null,
)

interface PartyEligibilityClient {
    suspend fun eligibilityOf(partyId: UUID): PartyEligibility
}

data class ScaChallengeSnapshot(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

interface ScaChallengeClient {
    suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot

    /**
     * Spend the challenge (sca-service's compare-and-consume gate, RTS Art. 5 single-use).
     * sca-service re-checks the party, refuses a challenge that is not COMPLETED, and marks
     * it consumed under a compare-and-set — so two concurrent offers cannot both succeed on
     * one challenge. Reading `status == "COMPLETED"` alone never gave that: it is a fact that
     * stays true forever, which is what made one ceremony reusable for unlimited grants.
     */
    suspend fun consumeChallenge(challengeId: UUID, expectedPartyId: UUID): ScaChallengeSnapshot
}

/**
 * ADR-0232 D5 / threat model T1 — does the grantor actually own the resource they are sharing?
 *
 * Nothing else in the chain asks this. delegation-service checked only that both parties exist
 * and are KYC'd, and the product-service projections key their guard on
 * (resource, grantee) — a grant row is authority in itself there. So a grant naming a stranger's
 * accountId produced real, enforced access to that account: two consenting parties could mint
 * payment rights over a third party's money using nothing but their own valid SCA. This gate is
 * the missing half; the other half (the projection comparing the grant's grantor to the resource
 * owner) belongs to the consuming service.
 */
enum class OwnershipVerdict { OWNED, NOT_OWNED, UNVERIFIABLE }

interface ResourceOwnershipClient {
    suspend fun verifyOwnership(
        grantorPartyId: UUID,
        resourceType: DelegationResourceType,
        resourceId: UUID,
    ): OwnershipVerdict
}
