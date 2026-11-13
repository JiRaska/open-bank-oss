// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.domain.event.AccountStatusChangedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.FreezeOrigin
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PartyStandingServiceTest {

    private val partyId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val accountRepository: AccountRepository = mockk()
    private val eventPublisher: AccountEventPublisher = mockk(relaxed = true)
    private val service = PartyStandingService(accountRepository, eventPublisher, Clock.fixed(now, ZoneOffset.UTC))

    private fun account(status: AccountStatus, origin: FreezeOrigin? = null) = Account(
        id = UUID.randomUUID(),
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = partyId,
        productId = UUID.randomUUID(),
        currency = CurrencyCode.CZK,
        status = status,
        openedAt = Instant.parse("2026-01-01T00:00:00Z"),
        closedAt = null,
        version = 3L,
        freezeOrigin = origin,
    )

    private fun owns(vararg accounts: Account) {
        coEvery { accountRepository.findByPartyId(partyId, any(), null) } returns accounts.toList()
        coEvery { accountRepository.update(any()) } answers { firstArg<Account>().copy(version = 4L) }
    }

    @Test
    fun `losing standing freezes the ACTIVE accounts only and says why on the event`(): Unit = runBlocking {
        val active = account(AccountStatus.ACTIVE)
        val pending = account(AccountStatus.PENDING_ACTIVATION)
        val closed = account(AccountStatus.CLOSED)
        val operatorFrozen = account(AccountStatus.FROZEN, FreezeOrigin.OPERATOR)
        owns(active, pending, closed, operatorFrozen)
        val written = slot<Account>()
        val event = slot<Any>()

        val count = service.freezeAccountsOf(partyId, "SUSPENDED")

        assertThat(count).isEqualTo(1)
        coVerify(exactly = 1) { accountRepository.update(capture(written)) }
        assertThat(written.captured.id).isEqualTo(active.id)
        assertThat(written.captured.status).isEqualTo(AccountStatus.FROZEN)
        assertThat(written.captured.freezeOrigin).isEqualTo(FreezeOrigin.PARTY_STANDING)
        coVerify(exactly = 1) {
            eventPublisher.publish("openbank.accounts.account.status-changed", active.id.toString(), capture(event))
        }
        val published = event.captured as AccountStatusChangedEvent
        assertThat(published.previousStatus).isEqualTo(AccountStatus.ACTIVE)
        assertThat(published.newStatus).isEqualTo(AccountStatus.FROZEN)
        assertThat(published.reason).isEqualTo("PARTY_SUSPENDED")
        assertThat(published.version).isEqualTo(4L)
        assertThat(published.occurredAt).isEqualTo(now)
    }

    @Test
    fun `a redelivered suspension changes and publishes nothing`(): Unit = runBlocking {
        owns(account(AccountStatus.FROZEN, FreezeOrigin.PARTY_STANDING))

        assertThat(service.freezeAccountsOf(partyId, "SUSPENDED")).isZero()

        coVerify(exactly = 0) { accountRepository.update(any()) }
        coVerify(exactly = 0) { eventPublisher.publish(any(), any(), any()) }
    }

    @Test
    fun `good standing lifts the party freezes and leaves every other freeze alone`(): Unit = runBlocking {
        val partyFrozen = account(AccountStatus.FROZEN, FreezeOrigin.PARTY_STANDING)
        val operatorFrozen = account(AccountStatus.FROZEN, FreezeOrigin.OPERATOR)
        val frozenBeforeOriginExisted = account(AccountStatus.FROZEN, origin = null)
        owns(partyFrozen, operatorFrozen, frozenBeforeOriginExisted, account(AccountStatus.ACTIVE))
        val written = slot<Account>()
        val event = slot<Any>()

        val count = service.restoreAccountsOf(partyId)

        assertThat(count).isEqualTo(1)
        coVerify(exactly = 1) { accountRepository.update(capture(written)) }
        assertThat(written.captured.id).isEqualTo(partyFrozen.id)
        assertThat(written.captured.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(written.captured.freezeOrigin).isNull()
        coVerify(exactly = 1) { eventPublisher.publish(any(), partyFrozen.id.toString(), capture(event)) }
        assertThat((event.captured as AccountStatusChangedEvent).reason).isEqualTo("PARTY_ACTIVE")
    }

    @Test
    fun `every page of a party's accounts is covered`(): Unit = runBlocking {
        val firstPage = List(50) { account(AccountStatus.ACTIVE) }
        val secondPage = listOf(account(AccountStatus.ACTIVE))
        coEvery { accountRepository.findByPartyId(partyId, 50, null) } returns firstPage
        coEvery { accountRepository.findByPartyId(partyId, 50, firstPage.last().id) } returns secondPage
        coEvery { accountRepository.update(any()) } answers { firstArg() }

        assertThat(service.freezeAccountsOf(partyId, "CLOSED")).isEqualTo(51)
    }
}
