// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.e2e

import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.model.DelegationResourceType
import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.ws.rs.NotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile-local in-memory replacements for the four downstream ports the ADR-0232 offer ceremony
 * chains. The real wire shapes are the pact suite's subject; these alternatives exist so the E2E
 * can prove gate ORDER and ATOMICITY (a refused offer must not spend the customer's SCA ceremony,
 * a spent challenge is single-use) without standing up sca-/pid-/party-services.
 */

@Alternative
@ApplicationScoped
class E2EScaChallengeClient : ScaChallengeClient {
    private val challenges = ConcurrentHashMap<UUID, ScaChallengeSnapshot>()
    private val spent = ConcurrentHashMap.newKeySet<UUID>()

    fun seed(id: UUID, partyId: UUID, purpose: String, status: String = "COMPLETED") {
        challenges[id] = ScaChallengeSnapshot(id, partyId, purpose, status)
    }

    fun isSpent(id: UUID): Boolean = id in spent

    override suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot =
        challenges[challengeId] ?: throw NotFoundException("challenge $challengeId not found")

    override suspend fun consumeChallenge(
        challengeId: UUID,
        expectedPartyId: UUID,
        reference: String?,
    ): ScaChallengeSnapshot {
        val challenge = getChallenge(challengeId)
        check(challenge.status == "COMPLETED") { "challenge $challengeId is not completed" }
        // sca-service answers 409 here (compare-and-consume); the caller surfaces it as its own
        // ceremony failure — modelling that keeps single-use observable end to end.
        check(spent.add(challengeId)) { "challenge $challengeId has already been consumed" }
        return challenge
    }
}

@Alternative
@ApplicationScoped
class E2EPartyEligibilityClient : PartyEligibilityClient {
    private val overrides = ConcurrentHashMap<UUID, PartyEligibility>()

    fun seed(eligibility: PartyEligibility) {
        overrides[eligibility.partyId] = eligibility
    }

    override suspend fun eligibilityOf(partyId: UUID): PartyEligibility = overrides[partyId] ?: PartyEligibility(
        partyId = partyId,
        active = true,
        kycLevel = "FULL",
        displayName = "Party ${partyId.toString().substring(0, 8)}",
        partyType = "INDIVIDUAL",
    )
}

@Alternative
@ApplicationScoped
class E2EGrantorAuthorityClient : GrantorAuthorityClient {
    private val overrides = ConcurrentHashMap<UUID, GrantorAuthority>()

    fun refuse(principalPartyId: UUID, verdict: GrantorAuthorityVerdict) {
        overrides[principalPartyId] = GrantorAuthority(verdict)
    }

    override suspend fun authorityFor(principalPartyId: UUID, actorPartyId: UUID): GrantorAuthority =
        overrides[principalPartyId] ?: GrantorAuthority(
            verdict = GrantorAuthorityVerdict.AUTHORIZED,
            displayName = "Grantor ${principalPartyId.toString().substring(0, 8)}",
            partyType = "INDIVIDUAL",
        )
}

@Alternative
@ApplicationScoped
class E2EResourceOwnershipClient : ResourceOwnershipClient {
    private val verdicts = ConcurrentHashMap<String, OwnershipVerdict>()

    fun refuse(
        grantorPartyId: UUID,
        resourceType: DelegationResourceType,
        resourceId: UUID,
        verdict: OwnershipVerdict,
    ) {
        verdicts[key(grantorPartyId, resourceType, resourceId)] = verdict
    }

    override suspend fun verifyOwnership(
        grantorPartyId: UUID,
        resourceType: DelegationResourceType,
        resourceId: UUID,
    ): OwnershipVerdict = verdicts[key(grantorPartyId, resourceType, resourceId)] ?: OwnershipVerdict.OWNED

    private fun key(grantorPartyId: UUID, resourceType: DelegationResourceType, resourceId: UUID): String =
        "$grantorPartyId:$resourceType:$resourceId"
}

class E2EProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.http.test-port" to "0",
    )

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        E2EScaChallengeClient::class.java,
        E2EPartyEligibilityClient::class.java,
        E2EGrantorAuthorityClient::class.java,
        E2EResourceOwnershipClient::class.java,
    )
}
