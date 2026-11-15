// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.CatalogProduct
import com.openbank.account.application.port.out.PassportCommitment
import com.openbank.account.application.port.out.PassportIntent
import com.openbank.account.application.port.out.PassportOperation
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
import com.openbank.account.application.port.out.PublishedRevision
import com.openbank.account.application.port.out.RevisionResolution
import com.openbank.account.application.port.out.SanctionsScreenResult
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.ProductRevisionPin
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.money.CurrencyCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PassportAccountRecoveryTest {
    private val repo = mockk<AccountRepository>()
    private val passport = mockk<PassportCommitment>()
    private val pinning = mockk<ProductCatalogPort>()
    private val screening = mockk<AccountSanctionsScreeningPort>()
    private val iban = Iban.of("CZ6508000000192000145399")
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val product = UUID.randomUUID()
    private val party = UUID.randomUUID()
    private val grant = UUID.randomUUID()
    private val offering = UUID.randomUUID()
    private val revision = UUID.randomUUID()
    private val binding = "a".repeat(64)
    private val prepared = mutableMapOf<String, PassportIntent>()
    private val consumed = mutableListOf<PassportIntent>()
    private val pin = ProductRevisionPin(offering, "CURRENT", revision, 1, null, null, null, now)

    init {
        coEvery { passport.find(any()) } answers { prepared[firstArg()] }
        coEvery { passport.prepare(any()) } answers {
            val intent = firstArg<PassportIntent>()
            prepared.getOrPut(intent.key) { intent }
        }
        coEvery { passport.stageOpening(any(), any()) } returns Unit
        coEvery { passport.admit(any()) } answers { consumed += firstArg<PassportIntent>() }
        coEvery { screening.screen(any(), any(), any()) } returns SanctionsScreenResult("CLEAR", 0.0, null)
        coEvery { pinning.findById(product) } returns
            ProductLookupResult.Found(CatalogProduct(product, "CURRENT", "ACTIVE", "CZK"))
        coEvery { pinning.resolvePublishedRevision(product, now) } returns
            RevisionResolution.Published(PublishedRevision(revision, offering, 1, null, null, null))
    }

    @Test
    fun `opening resumes the same admitted intent after failure before the account write`() {
        val command = OpenAccountCommand(
            idempotencyKey = "synthetic-opening",
            requestHash = binding,
            partyId = party,
            productId = product,
            accountType = AccountType.CURRENT,
            currency = CurrencyCode.of("CZK"),
            requestedBy = UUID.randomUUID(),
            legalName = "Synthetic Applicant",
            passportGrantId = grant,
        )
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.existsByIban(iban) } returns false
        coEvery { repo.saveNewAccount(any(), any(), any(), any(), any()) } throws InterruptedWrite()

        assertThrows(InterruptedWrite::class.java) { runBlocking { service().openAccount(command) } }
        val first = consumed.single()
        coEvery { repo.saveNewAccount(any(), any(), any(), any(), any()) } answers { firstArg() }

        val opened = runBlocking { service().openAccount(command) }
        assertEquals(first.accountId, opened.id)
        assertEquals(AccountStatus.PENDING_ACTIVATION, opened.status)
        assertEquals(first.pin, opened.productRevisionPin)
        assertEquals(listOf(first, first), consumed)
        coVerify(exactly = 1) { pinning.resolvePublishedRevision(any(), any()) }
    }

    @Test
    fun `activation resumes the same admitted intent and commits its event with the write`() {
        val pending = Account(
            id = UUID.randomUUID(),
            accountNumber = iban,
            accountType = AccountType.CURRENT,
            partyId = party,
            productId = product,
            currency = CurrencyCode.of("CZK"),
            status = AccountStatus.PENDING_ACTIVATION,
            openedAt = now,
            closedAt = null,
            version = 0,
            productRevisionPin = pin,
        )
        val opening = PassportIntent(
            "OPEN:test",
            UUID.randomUUID(),
            pending.id,
            grant,
            party,
            PassportOperation.OPEN,
            binding,
            pin,
        )
        coEvery { passport.openingFor(pending.id) } returns opening
        coEvery { repo.findById(pending.id) } returns pending
        coEvery { repo.update(any(), any()) } throws InterruptedWrite()

        assertThrows(InterruptedWrite::class.java) { runBlocking { service().activateAccount(pending.id) } }
        val first = consumed.single()
        coEvery { repo.update(any(), any()) } answers {
            val committed = firstArg<Account>().copy(version = 1)
            val event = checkNotNull(secondArg<((Account) -> DomainEvent)?>())
            assertEquals("AccountStatusChanged", event(committed).eventType)
            committed
        }

        assertEquals(AccountStatus.ACTIVE, runBlocking { service().activateAccount(pending.id) }.status)
        assertEquals(listOf(first, first), consumed)
    }

    private fun service() = AccountService(
        repo,
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk<IbanGenerator>().also { every { it.generate(any()) } returns iban },
        mockk(relaxed = true),
        screening,
        ProductRevisionPinning(pinning) {},
        mockk(relaxed = true),
        Clock.fixed(now, ZoneOffset.UTC),
        mockk(relaxed = true),
        passport,
    )

    private class InterruptedWrite : RuntimeException()
}
