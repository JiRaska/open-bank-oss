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
    suspend fun save(grant: DelegationGrant): DelegationGrant
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
    fun markExpired(id: UUID, expiredAt: OffsetDateTime, event: DomainEvent): Uni<Boolean>
}

/** ADR-0232 D5 — what the eligibility gate needs to know about a party, nothing more. */
data class PartyEligibility(val partyId: UUID, val active: Boolean, val kycLevel: String)

interface PartyEligibilityClient {
    suspend fun eligibilityOf(partyId: UUID): PartyEligibility
}

data class ScaChallengeSnapshot(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

interface ScaChallengeClient {
    suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot
}
