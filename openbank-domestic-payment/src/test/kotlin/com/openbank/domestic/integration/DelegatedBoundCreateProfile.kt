// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.out.AccountLookupPort
import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID

/**
 * Enables [DeterministicAccountLookup] for the bound-create IT only. Stateless on purpose: a
 * QuarkusTestProfile loads in a different classloader from the test, so a shared mutable map would
 * be two maps. Both sides derive the same ids from the same inputs instead.
 */
class DelegatedBoundCreateProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(DeterministicAccountLookup::class.java)
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
