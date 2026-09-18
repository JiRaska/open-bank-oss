// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.PartyMandateProjectionRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.PartyMandateProjection
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class BusinessPaymentAuthorityServiceTest {
    private val accountId = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val human = UUID.randomUUID()
    private val accounts: AccountRepository = mockk()
    private val mandates: PartyMandateProjectionRepository = mockk()
    private val service = BusinessPaymentAuthorityService(accounts, mandates)

    private fun mandate(authority: String, signatures: Int?) = PartyMandateProjection(
        id = UUID.randomUUID(),
        principalPartyId = company,
        agentPartyId = human,
        authority = authority,
        requiredSignatures = signatures,
        active = true,
    )

    private fun owner() {
        coEvery { accounts.findById(accountId) } returns mockk<Account> {
            io.mockk.every { partyId } returns company
        }
    }

    @Test
    fun `exact active sole mandate permits one human decision`(): Unit = runBlocking {
        owner()
        coEvery { mandates.findActive(company, human) } returns listOf(mandate("SOLE", 1))
        val decision = service.decide(accountId, human)
        assertThat(decision.authorized).isTrue()
        assertThat(decision.ownerPartyId).isEqualTo(company)
    }

    @Test
    fun `joint and unknown thresholds refuse unilateral debit`(): Unit = runBlocking {
        owner()
        for (candidates in listOf(
            listOf(mandate("JOINT", 2)),
            listOf(mandate("JOINT", null)),
            listOf(mandate("SOLE", null)),
            listOf(mandate("SOLE", 1), mandate("JOINT", 2)),
        )) {
            coEvery { mandates.findActive(company, human) } returns candidates
            val decision = service.decide(accountId, human)
            assertThat(decision.authorized).isFalse()
            assertThat(decision.outcome).isEqualTo(BusinessPaymentAuthorityOutcome.APPROVAL_REQUIRED)
        }
    }

    @Test
    fun `missing mandate or account refuses without inventing owner`(): Unit = runBlocking {
        owner()
        coEvery { mandates.findActive(company, human) } returns emptyList()
        assertThat(service.decide(accountId, human).outcome).isEqualTo(BusinessPaymentAuthorityOutcome.NO_MANDATE)
        coEvery { accounts.findById(accountId) } returns null
        val missing = service.decide(accountId, human)
        assertThat(missing.authorized).isFalse()
        assertThat(missing.ownerPartyId).isNull()
    }
}
