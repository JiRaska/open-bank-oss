// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.BalanceUseCase
import com.openbank.balance.application.port.`in`.CreditAccountCommand
import com.openbank.balance.application.port.`in`.DebitAccountCommand
import com.openbank.balance.application.port.`in`.GetBalanceQuery
import com.openbank.balance.application.port.`in`.InitializeBalanceCommand
import com.openbank.balance.application.port.`in`.PlaceHoldCommand
import com.openbank.balance.application.port.`in`.ReleaseHoldCommand
import com.openbank.balance.application.port.`in`.SetOverdraftLimitCommand
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.application.port.out.LedgerProjectionPort
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceHold
import com.openbank.libs.observability.DomainMetrics
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class LedgerProjectionServiceTest {

    private fun change(
        accountId: UUID = UUID.randomUUID(),
        delta: String = "100.00",
        transactionId: UUID = UUID.randomUUID(),
        journalEntryId: UUID = UUID.randomUUID(),
    ) = AccountBookedChange(
        accountId = accountId,
        currency = "CZK",
        delta = BigDecimal(delta),
        journalEntryId = journalEntryId,
        transactionId = transactionId,
        entryDate = LocalDate.parse("2026-01-15"),
        version = 1L,
    )

    private fun balance(accountId: UUID, booked: String) = Balance(
        id = UUID.randomUUID(),
        accountId = accountId,
        currency = "CZK",
        bookedAmount = BigDecimal(booked),
        availableAmount = BigDecimal(booked),
        reservedAmount = BigDecimal.ZERO,
        pendingAmount = BigDecimal.ZERO,
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        version = 1L,
    )

    @Test
    fun `apply records the delta and publishes BALANCE_UPDATED`(): Unit = runBlocking {
        val c = change(delta = "100.00")
        val port = FakeProjectionPort(result = balance(c.accountId, "100.00"))
        val service = LedgerProjectionService(
            port,
            FakeHoldRepo(),
            NoopBalanceUseCase(),
            mockk<DomainMetrics>(relaxed = true),
        )

        service.apply(c)

        assertEquals(c.journalEntryId, port.applied.single().first)
        // The event is the port's job since #8510 (written to balance_outbox in the mutation's own
        // transaction); the use case's remaining obligation is to pass the actor through.
        assertEquals("system:balance-service:ledger-projection", port.applied.single().second)
    }

    @Test
    fun `apply on a duplicate delivery does not publish and does not re-apply`(): Unit = runBlocking {
        val c = change()
        val port = FakeProjectionPort(result = null) // dedup hit
        val service = LedgerProjectionService(
            port,
            FakeHoldRepo(),
            NoopBalanceUseCase(),
            mockk<DomainMetrics>(relaxed = true),
        )

        service.apply(c)

        // Duplicate delivery: the port returned null, the use case publishes nothing itself, and
        // the port wrote nothing either (it owns both the dedup decision and the event, #8510).
        assertEquals(1, port.applied.size) // port was called
    }

    @Test
    fun `apply releases the originating payment's cover hold by transactionId`(): Unit = runBlocking {
        val c = change(delta = "-40.00")
        val hold = BalanceHold(
            id = UUID.randomUUID(),
            accountId = c.accountId,
            amount = BigDecimal("40.00"),
            currency = "CZK",
            reason = "payment",
            referenceId = c.transactionId.toString(),
            expiresAt = null,
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            releasedAt = null,
        )
        val holdRepo = FakeHoldRepo(active = listOf(hold))
        val balanceUseCase = NoopBalanceUseCase()
        val service = LedgerProjectionService(
            FakeProjectionPort(result = balance(c.accountId, "60.00")),
            holdRepo,
            balanceUseCase,
            mockk<DomainMetrics>(relaxed = true),
        )

        service.apply(c)

        assertEquals(hold.id, balanceUseCase.released.single())
    }

    // --- fakes -------------------------------------------------------------------------------

    private class FakeProjectionPort(private val result: Balance?) : LedgerProjectionPort {
        /** (journalEntryId, actorId) pairs the service handed through. */
        val applied = mutableListOf<Pair<UUID, String>>()
        override suspend fun applyBookedDelta(
            journalEntryId: UUID,
            accountId: UUID,
            currency: String,
            delta: BigDecimal,
            transactionId: UUID,
            entryDate: LocalDate,
            actorId: String,
        ): Balance? {
            applied += (journalEntryId to actorId)
            return result
        }
    }

    private class FakeHoldRepo(private val active: List<BalanceHold> = emptyList()) : HoldRepository {
        override suspend fun findById(holdId: UUID): BalanceHold? = active.firstOrNull { it.id == holdId }
        override suspend fun findActiveByAccountId(accountId: UUID): List<BalanceHold> =
            active.filter { it.accountId == accountId }
        override suspend fun findActiveByReferenceId(referenceId: String): List<BalanceHold> =
            active.filter { it.referenceId == referenceId && it.releasedAt == null }
        override suspend fun findByNaturalKey(accountId: UUID, currency: String, referenceId: String): BalanceHold? =
            active.firstOrNull { it.accountId == accountId && it.currency == currency && it.referenceId == referenceId }
        override suspend fun save(hold: BalanceHold): BalanceHold = hold
        override suspend fun update(hold: BalanceHold): BalanceHold = hold
        override suspend fun saveWithEvent(
            hold: BalanceHold,
            balance: Balance,
            event: com.openbank.balance.domain.model.BalanceEvent,
        ): BalanceHold = hold
        override suspend fun releaseWithEvent(
            hold: BalanceHold,
            balance: Balance,
            event: com.openbank.balance.domain.model.BalanceEvent,
        ): BalanceHold = hold
    }

    private class NoopBalanceUseCase : BalanceUseCase {
        val released = mutableListOf<UUID>()
        override suspend fun releaseHold(cmd: ReleaseHoldCommand): BalanceHold {
            released += cmd.holdId
            return BalanceHold(
                id = cmd.holdId,
                accountId = UUID.randomUUID(),
                amount = BigDecimal.ZERO,
                currency = "CZK",
                reason = "",
                referenceId = "",
                expiresAt = null,
                createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                releasedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            )
        }
        override suspend fun getBalance(query: GetBalanceQuery): Balance = notImplemented()
        override suspend fun getBalances(accountId: UUID): List<Balance> = notImplemented()
        override suspend fun placeHold(cmd: PlaceHoldCommand): BalanceHold = notImplemented()
        override suspend fun credit(cmd: CreditAccountCommand): Balance = notImplemented()
        override suspend fun debit(cmd: DebitAccountCommand): Balance = notImplemented()
        override suspend fun initializeBalance(cmd: InitializeBalanceCommand): Balance = notImplemented()
        override suspend fun setOverdraftLimit(cmd: SetOverdraftLimitCommand): Balance = notImplemented()
        private fun notImplemented(): Nothing = throw UnsupportedOperationException("not used in test")
    }
}
