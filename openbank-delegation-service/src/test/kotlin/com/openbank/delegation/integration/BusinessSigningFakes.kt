// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.ActingForEntity
import com.openbank.delegation.application.port.out.ApprovalLink
import com.openbank.delegation.application.port.out.ApprovalScaVerifier
import com.openbank.delegation.application.port.out.MandateDirectory
import com.openbank.delegation.application.port.out.ScaVerdict
import com.openbank.delegation.domain.model.MandateAuthority
import com.openbank.delegation.domain.model.RepresentationMandate
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only seams for the two outbound systems. QuarkusMock cannot install a fake that is not a
 * subclass of the production bean, so these alternatives delegate to whatever fake a test sets.
 * No other test in the module reaches a business-signing path, and one that did without setting a
 * fake fails loudly rather than silently reading an empty register.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class SwitchableMandateDirectory : MandateDirectory {
    @Volatile
    var delegate: MandateDirectory? = null

    private fun target() = checkNotNull(delegate) { "no FakeMandateDirectory installed" }

    override suspend fun activeMandates(entityPartyId: UUID) = target().activeMandates(entityPartyId)

    override suspend fun actingFor(humanPartyId: UUID) = target().actingFor(humanPartyId)

    override suspend fun entityName(entityPartyId: UUID) = target().entityName(entityPartyId)

    override suspend fun personName(partyId: UUID) = target().personName(partyId)
}

@Alternative
@Priority(1)
@ApplicationScoped
class SwitchableApprovalSca : ApprovalScaVerifier {
    @Volatile
    var delegate: ApprovalScaVerifier? = null

    private fun target() = checkNotNull(delegate) { "no FakeApprovalSca installed" }

    override suspend fun verifyConsumedInitiatorChallenge(challengeId: UUID, partyId: UUID) =
        target().verifyConsumedInitiatorChallenge(challengeId, partyId)

    override suspend fun consumeApprovalChallenge(challengeId: UUID, partyId: UUID, link: ApprovalLink) =
        target().consumeApprovalChallenge(challengeId, partyId, link)
}

/**
 * party-service's register as a mutable in-memory fact, so a test can REVOKE a mandate between
 * creation and signing — the stale-mandate threat — and prove the live re-check sees it.
 */
class FakeMandateDirectory : MandateDirectory {
    val mandates = ConcurrentHashMap<UUID, MutableList<RepresentationMandate>>()
    val names = ConcurrentHashMap<UUID, String>()

    fun joint(entity: UUID, required: Int, vararg agents: UUID) {
        mandates[entity] = agents.map { RepresentationMandate(it, MandateAuthority.JOINT, required) }.toMutableList()
    }

    fun sole(entity: UUID, vararg agents: UUID) {
        mandates[entity] = agents.map { RepresentationMandate(it, MandateAuthority.SOLE, 1) }.toMutableList()
    }

    fun revoke(entity: UUID, agent: UUID) {
        mandates[entity]?.removeIf { it.agentPartyId == agent }
    }

    override suspend fun activeMandates(entityPartyId: UUID): List<RepresentationMandate> =
        mandates[entityPartyId]?.toList().orEmpty()

    val people = ConcurrentHashMap<UUID, String>()

    override suspend fun entityName(entityPartyId: UUID): String? = names[entityPartyId]

    override suspend fun personName(partyId: UUID): String? = people[partyId]

    override suspend fun actingFor(humanPartyId: UUID): List<ActingForEntity> =
        mandates.filterValues { list -> list.any { it.agentPartyId == humanPartyId } }
            .keys.map { ActingForEntity(it, names[it]) }
}

/**
 * sca-service as it will behave: an approval challenge is bound at creation to (party,
 * approvalRequestId, payloadSha256) and is consumable ONCE, and only by a consume stating exactly
 * that link. An initiator challenge is a consumed PAYMENT_INITIATION of one party.
 */
class FakeApprovalSca : ApprovalScaVerifier {
    data class Bound(val partyId: UUID, val approvalRequestId: UUID, val payloadSha256: String)

    val initiatorChallenges = ConcurrentHashMap<UUID, UUID>()
    val approvalChallenges = ConcurrentHashMap<UUID, Bound>()
    val consumed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val consumeCalls: MutableList<UUID> = java.util.Collections.synchronizedList(mutableListOf())

    fun initiator(party: UUID): UUID = UUID.randomUUID().also { initiatorChallenges[it] = party }

    fun approval(party: UUID, approvalId: UUID, sha: String): UUID =
        UUID.randomUUID().also { approvalChallenges[it] = Bound(party, approvalId, sha) }

    override suspend fun verifyConsumedInitiatorChallenge(challengeId: UUID, partyId: UUID): ScaVerdict =
        if (initiatorChallenges[challengeId] == partyId) ScaVerdict.VERIFIED else ScaVerdict.REFUSED

    override suspend fun consumeApprovalChallenge(challengeId: UUID, partyId: UUID, link: ApprovalLink): ScaVerdict {
        consumeCalls += challengeId
        val bound = approvalChallenges[challengeId] ?: return ScaVerdict.REFUSED
        val matches = bound.partyId == partyId &&
            bound.approvalRequestId == link.approvalRequestId &&
            bound.payloadSha256 == link.payloadSha256
        if (!matches || !consumed.add(challengeId)) return ScaVerdict.REFUSED
        return ScaVerdict.VERIFIED
    }
}
