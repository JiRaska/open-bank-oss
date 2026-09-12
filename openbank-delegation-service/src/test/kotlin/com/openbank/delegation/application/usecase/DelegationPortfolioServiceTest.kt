// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationPortfolioRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationPortfolio
import com.openbank.delegation.domain.model.DelegationResourceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationPortfolioServiceTest {
    private val repository = mockk<DelegationPortfolioRepository>()
    private val ownershipClient = mockk<ResourceOwnershipClient>()
    private val clock = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC)
    private val service = DelegationPortfolioService(repository, ownershipClient, clock)
    private val owner = UUID.randomUUID()

    @Test
    fun `a caller outside the resolved business profile cannot create a portfolio`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.create(UUID.randomUUID(), owner, "Treasury", setOf(UUID.randomUUID())) }
        }.isInstanceOf(DelegationPortfolioAccessDenied::class.java)

        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `a portfolio cannot be an empty client side grouping`(): Unit = runBlocking {
        assertThatThrownBy {
            DelegationPortfolio(
                ownerPartyId = owner,
                name = "Treasury",
                accountIds = emptySet(),
                createdAt = OffsetDateTime.now(clock),
                updatedAt = OffsetDateTime.now(clock),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("at least one account")
    }

    @Test
    fun `creation verifies every account against the active profile before persistence`(): Unit = runBlocking {
        val accounts = setOf(UUID.randomUUID(), UUID.randomUUID())
        coEvery { ownershipClient.verifyOwnership(owner, DelegationResourceType.ACCOUNT, any()) } returns
            OwnershipVerdict.OWNED
        coEvery { repository.save(any()) } answers { firstArg() }

        service.create(owner, owner, " Treasury ", accounts)

        accounts.forEach { accountId ->
            coVerify(exactly = 1) {
                ownershipClient.verifyOwnership(owner, DelegationResourceType.ACCOUNT, accountId)
            }
        }
        coVerify(exactly = 1) { repository.save(match { it.name == "Treasury" && it.accountIds == accounts }) }
    }

    @Test
    fun `creation refuses a foreign account without persisting the portfolio`(): Unit = runBlocking {
        val foreignAccount = UUID.randomUUID()
        coEvery {
            ownershipClient.verifyOwnership(owner, DelegationResourceType.ACCOUNT, foreignAccount)
        } returns OwnershipVerdict.NOT_OWNED

        assertThatThrownBy { runBlocking { service.create(owner, owner, "Treasury", setOf(foreignAccount)) } }
            .isInstanceOf(DelegationResourceOwnershipException::class.java)
            .hasMessageContaining("does not own account")

        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `creation fails closed when account ownership cannot be established`(): Unit = runBlocking {
        val account = UUID.randomUUID()
        coEvery { ownershipClient.verifyOwnership(owner, DelegationResourceType.ACCOUNT, account) } returns
            OwnershipVerdict.UNVERIFIABLE

        assertThatThrownBy { runBlocking { service.create(owner, owner, "Treasury", setOf(account)) } }
            .isInstanceOf(DelegationPortfolioOwnershipUnavailable::class.java)
            .hasMessageContaining("could not be established")

        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `read refuses a stale or different business principal even when it knows the portfolio id`(): Unit =
        runBlocking {
            val portfolio =
                DelegationPortfolio(
                    ownerPartyId = owner,
                    name = "Treasury",
                    accountIds = setOf(UUID.randomUUID()),
                    createdAt = OffsetDateTime.now(clock),
                    updatedAt = OffsetDateTime.now(clock),
                )
            coEvery { repository.findById(portfolio.id) } returns portfolio

            assertThatThrownBy { runBlocking { service.get(UUID.randomUUID(), portfolio.id) } }
                .isInstanceOf(DelegationPortfolioAccessDenied::class.java)
        }
}
