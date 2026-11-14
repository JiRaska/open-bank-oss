// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DelegationAuthorityPort
import com.openbank.libs.fincrime.admission.DelegationFact
import com.openbank.libs.fincrime.admission.DelegationFactsLookup
import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Enables [DeterministicAccountLookup] for the bound-create IT only. Stateless on purpose: a
 * QuarkusTestProfile loads in a different classloader from the test, so a shared mutable map would
 * be two maps. Both sides derive the same ids from the same inputs instead.
 */
class DelegatedBoundCreateProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives(): Set<Class<*>> =
        setOf(DeterministicAccountLookup::class.java, DeterministicDelegationAuthority::class.java)
}

/** Every IBAN is an internal account whose id and owner are pure functions of the IBAN. */
@Alternative
@ApplicationScoped
class DeterministicAccountLookup : AccountLookupPort {
    override suspend fun findPartyByIban(iban: String): UUID? = null

    override suspend fun findPartyByAccountId(accountId: UUID): UUID = ownerOf(accountId)

    override suspend fun findAccountIdByIban(iban: String): UUID = accountOf(iban)

    companion object {
        fun accountOf(iban: String): UUID = UUID.nameUUIDFromBytes("account:$iban".toByteArray())

        fun ownerOf(accountId: UUID): UUID = UUID.nameUUIDFromBytes("owner:$accountId".toByteArray())
    }
}

/**
 * Grant state as a pure function of the grantee: two literal grantees select the two refusals,
 * every other grantee holds one ACTIVE payment grant over exactly the account asked about. The
 * owner travels in the same lookup, as main's create path requires (ADR-0375 D1).
 */
@Alternative
@ApplicationScoped
class DeterministicDelegationAuthority : DelegationAuthorityPort {
    override suspend fun factsFor(accountId: UUID, partyId: UUID): DelegationFactsLookup {
        val grant = DelegationFact(
            grantId = UUID.nameUUIDFromBytes("grant:$accountId:$partyId".toByteArray()),
            grantorPartyId = DeterministicAccountLookup.ownerOf(accountId),
            resourceId = if (partyId ==
                OTHER_ACCOUNT_GRANTEE
            ) {
                UUID.nameUUIDFromBytes("elsewhere".toByteArray())
            } else {
                accountId
            },
            granteePartyId = partyId,
            capabilities = setOf("ACCOUNT_INITIATE_PAYMENT"),
            active = partyId != REVOKED_GRANTEE,
            validFrom = OffsetDateTime.now().minusDays(1),
            validTo = null,
        )
        return DelegationFactsLookup.Available(listOf(grant), DeterministicAccountLookup.ownerOf(accountId))
    }

    companion object {
        val REVOKED_GRANTEE: UUID = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110")
        val OTHER_ACCOUNT_GRANTEE: UUID = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
    }
}
