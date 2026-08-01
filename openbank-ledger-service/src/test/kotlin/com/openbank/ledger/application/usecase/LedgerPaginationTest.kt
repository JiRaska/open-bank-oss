// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.ListJournalsQuery
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LedgerPaginationTest {

    private val journalRepository = mockk<JournalRepository>()
    private val glAccountRepository = mockk<GlAccountRepository>(relaxed = true)
    private val service = LedgerService(
        journalRepository,
        glAccountRepository,
        ObjectMapper(),
        mockk<com.openbank.libs.observability.DomainMetrics>(relaxed = true),
        mockk<com.openbank.ledger.application.port.out.YearCloseRepository>(relaxed = true),
        mockk<AccountingDayLock>(relaxed = true),
        mockk<PeriodFreezeLock>(relaxed = true),
        java.time.Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), java.time.ZoneOffset.UTC),
    )

    @Test
    fun `list journals forwards decoded cursor and emits cursor for final item on current page`(): Unit = runBlocking {
        val afterId = UUID.randomUUID()
        val entries = listOf(
            journalEntry(UUID.randomUUID(), 1L),
            journalEntry(UUID.randomUUID(), 2L),
            journalEntry(UUID.randomUUID(), 3L),
        )

        coEvery {
            journalRepository.findByDateRange(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                3,
                afterId,
            )
        } returns entries

        val page = service.listJournals(
            ListJournalsQuery(
                fromDate = LocalDate.of(2026, 1, 1),
                toDate = LocalDate.of(2026, 1, 31),
                limit = 2,
                afterCursor = CursorEncoder.encode(afterId.toString()),
            ),
        )

        assertThat(page.data).containsExactly(entries[0], entries[1])
        assertThat(page.pagination.hasNextPage).isTrue()
        assertThat(CursorEncoder.decode(page.pagination.nextCursor!!)).isEqualTo(entries[1].id.toString())
    }

    private fun journalEntry(id: UUID, entryNumber: Long): JournalEntry {
        val journalId = id
        return JournalEntry(
            id = journalId,
            entryNumber = entryNumber,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, entryNumber.toInt()),
            valueDate = LocalDate.of(2026, 1, entryNumber.toInt()),
            description = "Entry $entryNumber",
            status = JournalStatus.POSTED,
            lines = listOf(
                JournalLine(
                    id = UUID.randomUUID(),
                    journalId = journalId,
                    glAccountId = UUID.randomUUID(),
                    side = JournalSide.DEBIT,
                    amount = Money.of(BigDecimal("100.00"), "CZK"),
                    fxRate = null,
                    baseAmount = Money.of(BigDecimal("100.00"), "CZK"),
                    sequence = 1,
                ),
                JournalLine(
                    id = UUID.randomUUID(),
                    journalId = journalId,
                    glAccountId = UUID.randomUUID(),
                    side = JournalSide.CREDIT,
                    amount = Money.of(BigDecimal("100.00"), "CZK"),
                    fxRate = null,
                    baseAmount = Money.of(BigDecimal("100.00"), "CZK"),
                    sequence = 2,
                ),
            ),
            createdAt = Instant.now(),
            createdBy = UUID.randomUUID(),
            version = 0L,
        )
    }
}
