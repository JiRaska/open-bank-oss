// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.account.domain.model.SavingsDelegationIntent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SavingsGoalDelegationGuardTest {

    private val accountRepository: AccountRepository = mockk()
    private val projectionRepository: DelegationProjectionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var guard: SavingsGoalDelegationGuard

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        guard = SavingsGoalDelegationGuard(accountRepository, projectionRepository, clock)
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account
    }

    private fun grant(capabilities: Set<String>, validTo: OffsetDateTime? = now.plusDays(30), active: Boolean = true) =
        DelegatedAccessGrant(
            id = UUID.randomUUID(),
            accountId = accountId,
            granteePartyId = delegate,
            capabilities = capabilities,
            resourceType = DelegatedAccessGrant.RESOURCE_TYPE_SAVINGS_GOAL,
            validFrom = now.minusDays(1),
            validTo = validTo,
            active = active,
        )

    @Test
    fun `owner passes with an empty projection`(): Unit = runBlocking {
        coEvery {
            projectionRepository.findActiveByAccountPartyAndType(accountId, owner, "SAVINGS_GOAL")
        } returns emptyList()
        assertThat(guard.isAuthorized(accountId, owner, SavingsDelegationIntent.WITHDRAW)).isTrue()
    }

    @Test
    fun `delegate with SAVINGS_DEPOSIT deposits but does not withdraw`(): Unit = runBlocking {
        coEvery {
            projectionRepository.findActiveByAccountPartyAndType(accountId, delegate, "SAVINGS_GOAL")
        } returns listOf(grant(setOf("SAVINGS_DEPOSIT")))
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.DEPOSIT)).isTrue()
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.WITHDRAW)).isFalse()
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.PROPOSE_WITHDRAW)).isFalse()
    }

    @Test
    fun `propose-only capability answers PROPOSE_WITHDRAW, never WITHDRAW`(): Unit = runBlocking {
        coEvery {
            projectionRepository.findActiveByAccountPartyAndType(accountId, delegate, "SAVINGS_GOAL")
        } returns listOf(grant(setOf("SAVINGS_PROPOSE_WITHDRAW")))
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.PROPOSE_WITHDRAW)).isTrue()
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.WITHDRAW)).isFalse()
    }

    @Test
    fun `expired or closed grant denies`(): Unit = runBlocking {
        coEvery {
            projectionRepository.findActiveByAccountPartyAndType(accountId, delegate, "SAVINGS_GOAL")
        } returns listOf(
            grant(setOf("SAVINGS_WITHDRAW"), validTo = now.minusDays(1)),
            grant(setOf("SAVINGS_WITHDRAW"), active = false),
        )
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.WITHDRAW)).isFalse()
    }

    @Test
    fun `unknown account denies`(): Unit = runBlocking {
        coEvery { accountRepository.findById(accountId) } returns null
        assertThat(guard.isAuthorized(accountId, delegate, SavingsDelegationIntent.DEPOSIT)).isFalse()
    }
}
