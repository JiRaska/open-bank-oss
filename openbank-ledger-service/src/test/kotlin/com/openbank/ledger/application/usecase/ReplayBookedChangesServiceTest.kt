// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesCommand
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ReplayBookedChangesServiceTest {

    private val from = LocalDate.of(2026, 6, 1)
    private val to = LocalDate.of(2026, 6, 30)
    private val clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC)

    private val journalRepository = mockk<JournalRepository>()
    private val objectMapper = mockk<ObjectMapper> { every { writeValueAsString(any()) } returns "{}" }

    private val service = ReplayBookedChangesService(journalRepository, objectMapper, clock)

    private val accountA = UUID.randomUUID()
    private val accountB = UUID.randomUUID()
    private val depositControlGl = UUID.randomUUID()
    private val cashClearingGl = UUID.randomUUID()

    /** A balanced entry that books +[amount] [currency] onto customer [account] (a deposit-control credit). */
    private fun bookingEntry(account: UUID, amount: String, currency: String = "CZK"): JournalEntry {
        val id = UUID.randomUUID()
        val money = Money.of(amount, currency)
        val cash = JournalLine(
            id = UUID.randomUUID(),
            journalId = id,
            glAccountId = cashClearingGl,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
        )
        val deposit = JournalLine(
            id = UUID.randomUUID(),
            journalId = id,
            glAccountId = depositControlGl,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = account,
        )
        return JournalEntry(
            id = id,
            entryNumber = 1L,
            transactionId = UUID.randomUUID(),
            entryDate = from,
            valueDate = from,
            description = "booking",
            status = JournalStatus.POSTED,
            lines = listOf(cash, deposit),
            createdAt = Instant.now(clock),
            createdBy = UUID.randomUUID(),
            version = 3L,
        )
    }

    @Test
    fun `dry-run tallies events and net delta but emits nothing`() = runBlocking<Unit> {
        coEvery { journalRepository.findByDateRange(from, to, any(), null) } returns
            listOf(bookingEntry(accountA, "100.00"), bookingEntry(accountB, "40.00"))

        val result = service.replay(ReplayBookedChangesCommand(from, to, dryRun = true))

        assertThat(result.dryRun).isTrue()
        assertThat(result.journalEntriesScanned).isEqualTo(2)
        assertThat(result.bookedChangeEvents).isEqualTo(2)
        assertThat(result.accountsTouched).isEqualTo(2)
        assertThat(result.netDeltaByCurrency).containsEntry("CZK", BigDecimal("140.00"))
        coVerify(exactly = 0) { journalRepository.appendOutbox(any()) }
    }

    @Test
    fun `real run enqueues one outbox message per booked delta with the routing metadata`() = runBlocking<Unit> {
        coEvery { journalRepository.findByDateRange(from, to, any(), null) } returns
            listOf(bookingEntry(accountA, "100.00"))
        val enqueued = slot<List<OutboxMessage>>()
        coEvery { journalRepository.appendOutbox(capture(enqueued)) } returns 1

        val result = service.replay(ReplayBookedChangesCommand(from, to, dryRun = false))

        assertThat(result.dryRun).isFalse()
        assertThat(result.bookedChangeEvents).isEqualTo(1)
        assertThat(enqueued.captured).singleElement().satisfies({
            assertThat(it.aggregateId).isEqualTo(accountA)
            assertThat(it.eventType).isEqualTo("AccountBookedChanged")
        })
    }

    @Test
    fun `pages through the repository until a short page ends the scan`() = runBlocking<Unit> {
        val fullPage = (1..500).map { bookingEntry(accountA, "1.00") }
        val lastId = fullPage.last().id
        coEvery { journalRepository.findByDateRange(from, to, any(), null) } returns fullPage
        coEvery { journalRepository.findByDateRange(from, to, any(), lastId) } returns
            listOf(bookingEntry(accountB, "2.00"))

        val result = service.replay(ReplayBookedChangesCommand(from, to, dryRun = true))

        assertThat(result.journalEntriesScanned).isEqualTo(501)
        assertThat(result.netDeltaByCurrency).containsEntry("CZK", BigDecimal("502.00"))
        coVerify(exactly = 1) { journalRepository.findByDateRange(from, to, any(), lastId) }
    }

    @Test
    fun `rejects an inverted window`() = runBlocking<Unit> {
        assertThatThrownBy {
            runBlocking { service.replay(ReplayBookedChangesCommand(to, from, dryRun = true)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
