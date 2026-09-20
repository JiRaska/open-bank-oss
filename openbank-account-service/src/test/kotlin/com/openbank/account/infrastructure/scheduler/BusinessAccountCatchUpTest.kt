// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DirectoryPage
import com.openbank.account.application.port.out.DirectoryParty
import com.openbank.account.application.port.out.PartyDirectoryPort
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Each guard of [BusinessAccountCatchUp], asserted by what reaches the account use case. */
class BusinessAccountCatchUpTest {

    private val parties = mockk<PartyDirectoryPort>()
    private val repository = mockk<AccountRepository>()
    private val useCase = mockk<AccountUseCase>(relaxed = true)
    private val registry = SimpleMeterRegistry()

    private fun job(openBusiness: Boolean = true, catchUp: Boolean = true, maxPages: Int = 20) = BusinessAccountCatchUp(
        parties = parties,
        accountRepository = repository,
        accountUseCase = useCase,
        registry = registry,
        domainMetrics = mockk(relaxed = true),
        openBusinessAccounts = openBusiness,
        catchUpEnabled = catchUp,
        pageSize = 100,
        maxPages = maxPages,
        businessProductId = BUSINESS_PRODUCT.toString(),
        businessCurrency = "EUR",
        systemActorId = "00000000-0000-0000-0000-0000000005ec",
    ).also { it.register() }

    private fun party(type: String = "SOLE_TRADER", status: String = "ACTIVE") =
        DirectoryParty(UUID.randomUUID(), type, status, "Acme")

    private fun serve(vararg p: DirectoryParty) {
        coEvery { parties.listActive(0, any()) } returns DirectoryPage(p.toList(), hasMore = false)
    }

    private fun account(type: AccountType, status: AccountStatus, product: UUID = BUSINESS_PRODUCT): Account = mockk {
        every { id } returns UUID.randomUUID()
        every { accountType } returns type
        every { this@mockk.status } returns status
        every { productId } returns product
    }

    @Test
    fun `an ACTIVE business party with no current account gets one under the shared key, then activation`(): Unit =
        runBlocking {
            val p = party("COMPANY")
            serve(p)
            val pending = account(AccountType.CURRENT, AccountStatus.PENDING_ACTIVATION)
            val pendingId = pending.id
            coEvery { repository.findByPartyId(p.partyId, any(), any()) } returnsMany
                listOf(emptyList(), listOf(pending))
            val cmds = mutableListOf<OpenAccountCommand>()
            coEvery { useCase.openAccount(capture(cmds)) } returns mockk(relaxed = true)

            assertThat(job().catchUpOnce()).isEqualTo(1)

            assertThat(cmds.single().idempotencyKey).isEqualTo("onboarding-business-account-${p.partyId}")
            assertThat(cmds.single().productId).isEqualTo(BUSINESS_PRODUCT)
            assertThat(cmds.single().currency.code).isEqualTo("EUR")
            coVerify(exactly = 1) { useCase.activateAccount(pendingId) }
            assertThat(
                registry.get("openbank.account.onboarding.business_accounts_opened_by_catch_up").counter().count(),
            )
                .isEqualTo(1.0)
        }

    @Test
    fun `an existing current account is neither re-opened nor re-activated`(): Unit = runBlocking {
        val p = party()
        serve(p)
        coEvery { repository.findByPartyId(p.partyId, any(), any()) } returns
            listOf(account(AccountType.CURRENT, AccountStatus.ACTIVE))

        assertThat(job().catchUpOnce()).isZero()
        coVerify(exactly = 0) { useCase.openAccount(any()) }
        coVerify(exactly = 0) { useCase.activateAccount(any()) }
    }

    @Test
    fun `retail, trust and not-yet-ACTIVE parties are skipped`(): Unit = runBlocking {
        serve(party("INDIVIDUAL"), party("TRUST"), party("COMPANY", status = "PENDING_KYC"))
        assertThat(job().catchUpOnce()).isZero()
        coVerify(exactly = 0) { repository.findByPartyId(any(), any(), any()) }
        coVerify(exactly = 0) { useCase.openAccount(any()) }
    }

    @Test
    fun `either flag off means party-service is never asked`(): Unit = runBlocking {
        job(openBusiness = false).tick()
        job(catchUp = false).tick()
        coVerify(exactly = 0) { parties.listActive(any(), any()) }
    }

    @Test
    fun `later pages are walked and the walk stops at max-pages`(): Unit = runBlocking {
        val onPage1 = party()
        coEvery { parties.listActive(0, any()) } returns DirectoryPage(listOf(party("INDIVIDUAL")), hasMore = true)
        coEvery { parties.listActive(1, any()) } returns DirectoryPage(listOf(onPage1), hasMore = true)
        coEvery { repository.findByPartyId(onPage1.partyId, any(), any()) } returns emptyList()
        coEvery { useCase.openAccount(any()) } returns mockk(relaxed = true)

        assertThat(job(maxPages = 2).catchUpOnce()).isEqualTo(1)
        coVerify(exactly = 0) { parties.listActive(2, any()) }
    }

    @Test
    fun `one party's failure does not starve the rest`(): Unit = runBlocking {
        val broken = party()
        val fine = party()
        serve(broken, fine)
        coEvery { repository.findByPartyId(broken.partyId, any(), any()) } throws IllegalStateException("db")
        coEvery { repository.findByPartyId(fine.partyId, any(), any()) } returns emptyList()
        coEvery { useCase.openAccount(any()) } returns mockk(relaxed = true)

        assertThat(job().catchUpOnce()).isEqualTo(1)
        coVerify(exactly = 1) { useCase.openAccount(match { it.partyId == fine.partyId }) }
    }

    @Test
    fun `party-service unreachable fails the tick instead of reading as nothing to do`() {
        coEvery { parties.listActive(any(), any()) } throws IllegalStateException("refused")
        assertThrows<IllegalStateException> { runBlocking { job().catchUpOnce() } }
    }

    private companion object {
        val BUSINESS_PRODUCT: UUID = UUID.fromString("d4275d2a-1343-3052-a6c0-8a99149b6c62")
    }
}
