// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.statement.Fixtures
import com.openbank.statement.application.port.out.AccountInfoPort
import com.openbank.statement.application.port.out.BalancePort
import com.openbank.statement.application.port.out.BookedEntryPort
import com.openbank.statement.application.port.out.PocketAccountInfo
import com.openbank.statement.application.port.out.StatementOutboxMessage
import com.openbank.statement.application.port.out.StatementPeriodRepository
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.PeriodCloseStatus
import com.openbank.statement.domain.model.StatementFormat
import com.openbank.statement.domain.model.StatementPeriod
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class StatementServiceTest {

    private val accountInfo = mockk<AccountInfoPort>()
    private val bookedEntries = mockk<BookedEntryPort>()
    private val balance = mockk<BalancePort>()
    private val periods = mockk<StatementPeriodRepository>(relaxed = false)

    private val from = LocalDate.parse("2026-01-01")
    private val to = LocalDate.parse("2026-01-31")

    private lateinit var service: StatementService
    private lateinit var restatement: StatementRestatementService

    @BeforeEach
    fun setUp() {
        service = StatementService(accountInfo, bookedEntries, balance, periods)
        service.clock = { Fixtures.CLOSED_AT }
        restatement = StatementRestatementService(accountInfo, bookedEntries, balance, periods)
        restatement.clock = { Fixtures.CLOSED_AT }
        every { accountInfo.pocketAccount(Fixtures.ACCOUNT_ID) } returns
            Uni.createFrom().item(PocketAccountInfo(Fixtures.ACCOUNT_ID, "CZ65", "Jan Novak", listOf("CZK")))
        every { bookedEntries.bookedEntries(any(), any(), any(), any()) } returns Uni.createFrom().item(
            listOf(
                Fixtures.entry(ref = "TX-1", amount = "100.00", cd = CreditDebit.CRDT),
                Fixtures.entry(ref = "TX-2", amount = "25.00", cd = CreditDebit.DBIT),
            ),
        )
        every { periods.priorClosing(any(), any(), any()) } returns Uni.createFrom().item(BigDecimal("1000.00"))
    }

    @Test
    fun `closing a month assigns the sequence, snapshots balances and emits the outbox event`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(7L)
        every { periods.saveWithOutbox(any(), any()) } answers { Uni.createFrom().item(firstArg<StatementPeriod>()) }

        val result = service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely()

        assertThat(result).hasSize(1)
        val p = result.first()
        assertThat(p.legalSequenceNumber).isEqualTo(7L)
        assertThat(p.openingBalance).isEqualByComparingTo("1000.00")
        assertThat(p.closingBalance).isEqualByComparingTo("1075.00")
        assertThat(p.entryCount).isEqualTo(2)
        assertThat(p.status).isEqualTo(PeriodCloseStatus.CLOSED)
        // Period + outbox event persist atomically in one call (no save()+append() split).
        verify(exactly = 1) {
            periods.saveWithOutbox(
                any<StatementPeriod>(),
                match<StatementOutboxMessage> { it.eventType == "account.statement.period.closed.v1" },
            )
        }
        verify(exactly = 0) { periods.save(any()) }
    }

    @Test
    fun `a re-run is idempotent - it returns the existing close without minting a new sequence`() {
        val existing = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 3, electronicSequenceNumber = 3,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(existing)

        val result = service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely()

        assertThat(result).containsExactly(existing)
        verify(exactly = 0) { periods.nextLegalSequence(any(), any()) }
        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `a closing balance that disagrees with balance-service fails the close - fail closed`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        // computed = 1000 + (100 - 25) = 1075, but balance-service reports 9999 -> mismatch
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("9999.00"), "CZK", to))

        assertThatThrownBy { service.closeMonth(Fixtures.ACCOUNT_ID, from, to).await().indefinitely() }
            .isInstanceOf(ReconciliationException::class.java)

        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `rendering a closed period replays it deterministically into camt-053`() {
        val period = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 7, electronicSequenceNumber = 7,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findBySequence(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns Uni.createFrom().item(period)

        val rendered = service.render(Fixtures.ACCOUNT_ID, "CZK", 7L, StatementFormat.CAMT_053)
            .await().indefinitely()

        assertThat(rendered.format).isEqualTo(StatementFormat.CAMT_053)
        assertThat(rendered.contentType).isEqualTo("application/xml")
        assertThat(rendered.body).contains("<LglSeqNb>7</LglSeqNb>")
        assertThat(rendered.body).contains("<CreDtTm>2026-02-01T02:30:00Z</CreDtTm>")
    }

    @Test
    fun `rendering a missing sequence fails with not-found`() {
        every { periods.findBySequence(any(), any(), any()) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.render(Fixtures.ACCOUNT_ID, "CZK", 99L, StatementFormat.MT940).await().indefinitely()
        }.isInstanceOf(StatementNotFoundException::class.java)
    }

    @Test
    fun `summary returns the same closed period as render, but as the canonical model - no renderer`() {
        val period = StatementPeriod(
            id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
            periodFrom = from, periodTo = to, legalSequenceNumber = 7, electronicSequenceNumber = 7,
            openingBalance = BigDecimal("1000.00"), closingBalance = BigDecimal("1075.00"),
            entryCount = 2, closedAt = Fixtures.CLOSED_AT,
        )
        every { periods.findBySequence(Fixtures.ACCOUNT_ID, "CZK", 7L) } returns Uni.createFrom().item(period)

        val model = service.summary(Fixtures.ACCOUNT_ID, "CZK", 7L).await().indefinitely()

        assertThat(model.legalSequenceNumber).isEqualTo(7L)
        assertThat(model.openingBalance.amount).isEqualByComparingTo("1000.00")
        assertThat(model.closingBalance.amount).isEqualByComparingTo("1075.00")
        assertThat(model.entries).hasSize(2)
        assertThat(model.iban).isEqualTo("CZ65")
    }

    @Test
    fun `summary of a missing sequence fails with not-found, same as render`() {
        every { periods.findBySequence(any(), any(), any()) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.summary(Fixtures.ACCOUNT_ID, "CZK", 99L).await().indefinitely()
        }.isInstanceOf(StatementNotFoundException::class.java)
    }

    @Test
    fun `an ad-hoc export carries no legal sequence`() {
        val rendered = service.export(Fixtures.ACCOUNT_ID, "CZK", from, to, StatementFormat.PDF)
            .await().indefinitely()

        // Non-sequenced informational export -> legal sequence 0 on the PDF.
        assertThat(rendered.body).contains("Statement no. (legal): 0")
        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    // ---- Restatement of a closed period (ADR-0035 §D, issue #1302 item 5) -------------------
    //
    // Before this, `PeriodCloseStatus.SUPERSEDED` had ZERO write sites fleet-wide: a period closed
    // on wrong data was immutable, and `closePocketMonth` silently returned the stale record.

    private fun standingClose(
        seq: Long = 3,
        opening: String = "1000.00",
        closing: String = "1075.00",
        entryCount: Int = 2,
    ) = StatementPeriod(
        id = UUID.randomUUID(), accountId = Fixtures.ACCOUNT_ID, pocketCurrency = "CZK",
        periodFrom = from, periodTo = to, legalSequenceNumber = seq, electronicSequenceNumber = seq,
        openingBalance = BigDecimal(opening), closingBalance = BigDecimal(closing),
        entryCount = entryCount, closedAt = Fixtures.CLOSED_AT,
    )

    @Test
    fun `restating a corrected period issues a new sequenced close referencing the superseded one`() {
        // The standing close was built before TX-2 (the 25.00 fee) was booked: it says 1100.00 with
        // one entry. The corrected truth is 1000 + 100 - 25 = 1075.00 over two entries.
        val standing = standingClose(seq = 3, closing = "1100.00", entryCount = 1)
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(standing)
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))
        every { periods.nextLegalSequence(Fixtures.ACCOUNT_ID, "CZK") } returns Uni.createFrom().item(4L)
        every { periods.supersedeAndReplace(any(), any(), any()) } answers {
            Uni.createFrom().item(secondArg<StatementPeriod>())
        }

        val restated = restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()

        // The FIGURES are the assertion, not merely that a status changed.
        assertThat(restated.closingBalance).isEqualByComparingTo("1075.00")
        assertThat(restated.openingBalance).isEqualByComparingTo("1000.00")
        assertThat(restated.entryCount).isEqualTo(2)
        assertThat(restated.legalSequenceNumber).isEqualTo(4L)
        assertThat(restated.electronicSequenceNumber).isEqualTo(4L)
        assertThat(restated.supersedesSequence).isEqualTo(3L)
        assertThat(restated.status).isEqualTo(PeriodCloseStatus.CLOSED)
        assertThat(restated.id).isNotEqualTo(standing.id)

        // The prior record is superseded in the SAME call (one transaction), and the correction is
        // announced on its own event type, not silently as another `period.closed`.
        verify(exactly = 1) {
            periods.supersedeAndReplace(
                standing.id,
                any(),
                match<StatementOutboxMessage> {
                    it.eventType == "account.statement.period.restated.v1" &&
                        it.payload.contains("\"supersedesSequence\":3") &&
                        it.payload.contains("\"closingBalance\":1075.00") &&
                        it.payload.contains("\"supersededClosingBalance\":1100.00")
                },
            )
        }
        // Never an in-place edit, and never a plain close.
        verify(exactly = 0) { periods.save(any()) }
        verify(exactly = 0) { periods.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `restating a period whose figures are unchanged burns no legal sequence`() {
        val standing = standingClose(seq = 3, closing = "1075.00", entryCount = 2)
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(standing)
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("1075.00"), "CZK", to))

        val result = restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()

        assertThat(result).isEqualTo(standing)
        verify(exactly = 0) { periods.nextLegalSequence(any(), any()) }
        verify(exactly = 0) { periods.supersedeAndReplace(any(), any(), any()) }
    }

    @Test
    fun `a restatement that cannot itself reconcile leaves the standing close untouched - fail closed`() {
        val standing = standingClose(seq = 3, closing = "1100.00", entryCount = 1)
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().item(standing)
        // computed = 1000 + (100 - 25) = 1075, balance-service says 9999 -> mismatch
        every { balance.closingBalance(Fixtures.ACCOUNT_ID, "CZK", to) } returns
            Uni.createFrom().item(BalanceAnchor(BigDecimal("9999.00"), "CZK", to))

        assertThatThrownBy {
            restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()
        }.isInstanceOf(ReconciliationException::class.java)

        verify(exactly = 0) { periods.supersedeAndReplace(any(), any(), any()) }
    }

    @Test
    fun `restating a window that was never closed is rejected, not turned into a first close`() {
        every { periods.findByPeriod(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            restatement.restatePocketPeriod(Fixtures.ACCOUNT_ID, "CZK", from, to).await().indefinitely()
        }.isInstanceOf(NoClosedPeriodToRestateException::class.java)

        verify(exactly = 0) { periods.nextLegalSequence(any(), any()) }
        verify(exactly = 0) { periods.supersedeAndReplace(any(), any(), any()) }
    }
}
