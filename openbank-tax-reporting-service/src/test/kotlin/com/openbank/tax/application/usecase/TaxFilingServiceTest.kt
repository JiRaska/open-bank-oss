// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.tax.application.port.out.ObservedRemittanceRepository
import com.openbank.tax.application.port.out.RemittanceTotals
import com.openbank.tax.application.port.out.TaxFilingRepository
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.FilingStatus
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxFilingRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TaxFilingServiceTest {

    private val july = FilingPeriod(2026, 7)

    // Fixed at 5 August 2026: July has ended, August has not — so one period is assemblable and
    // the current one is not, without the test depending on when it runs.
    private val clock = Clock.fixed(Instant.parse("2026-08-05T09:00:00Z"), ZoneOffset.UTC)

    private lateinit var remittances: ObservedRemittanceRepository
    private lateinit var filings: TaxFilingRepository
    private lateinit var service: TaxFilingService

    @BeforeEach
    fun setup() {
        remittances = mockk()
        filings = mockk()
        service = TaxFilingService(remittances, filings, AccountingClock.bank(clock), clock)
    }

    private fun remittance(id: UUID = UUID.randomUUID(), period: FilingPeriod = july) = ObservedRemittance(
        remittanceId = id,
        period = period,
        currency = "CZK",
        totalTaxAmount = BigDecimal("4115"),
        itemCount = 160,
        dueDate = period.dueDate,
        observedAt = Instant.parse("2026-08-01T06:00:00Z"),
    )

    @Nested
    inner class Observing {

        @Test
        fun `a first remittance opens its period and is recorded`(): Unit = runBlocking {
            coEvery { filings.openIfAbsent(any()) } answers { firstArg() }
            coEvery { remittances.record(any()) } returns true

            assertThat(service.observe(remittance())).isTrue()

            coVerify(exactly = 1) { filings.openIfAbsent(any()) }
        }

        /**
         * Kafka is at-least-once and this service is a second consumer group, so a redelivery after
         * a rebalance is routine. Counting a batch twice would overstate the tax on a statutory
         * return, so a duplicate must report false and change nothing.
         */
        @Test
        fun `a redelivered remittance is a no-op`(): Unit = runBlocking {
            coEvery { filings.openIfAbsent(any()) } answers { firstArg() }
            coEvery { remittances.record(any()) } returns false

            assertThat(service.observe(remittance())).isFalse()
        }

        /**
         * A batch for an already-assembled period is stored (the evidence must exist) but it does
         * NOT silently re-total the return — the assembled figures may already have been filed.
         */
        @Test
        fun `a late remittance into an assembled period is still recorded`(): Unit = runBlocking {
            val assembled = TaxFilingRecord.open(july, "CZK")
                .assemble(BigDecimal("4115"), 1, 160, "maker", Instant.parse("2026-08-02T06:00:00Z"))
            coEvery { filings.openIfAbsent(any()) } returns assembled
            coEvery { remittances.record(any()) } returns true

            assertThat(service.observe(remittance())).isTrue()
        }
    }

    @Nested
    inner class Assembling {

        @Test
        fun `assembling freezes the observed totals`(): Unit = runBlocking {
            coEvery { filings.findByPeriod(july) } returns TaxFilingRecord.open(july, "CZK")
            coEvery { remittances.totalsFor(july) } returns
                RemittanceTotals(3, 480, BigDecimal("12345"), setOf("CZK"))
            val saved = slot<TaxFilingRecord>()
            coEvery { filings.save(capture(saved), any()) } answers { firstArg() }

            val result = service.assemble(july, by = "maker")

            assertThat(result.status).isEqualTo(FilingStatus.ASSEMBLED)
            assertThat(saved.captured.totalTaxAmount).isEqualByComparingTo("12345")
            assertThat(saved.captured.remittanceCount).isEqualTo(3)
        }

        /**
         * A month still running would produce partial totals wearing the label of a return. The
         * check uses the ADR-0207 accounting-day authority, not a wall clock — a filing deadline is
         * an accounting date, and this is exactly the decision that must not depend on which clock
         * object is asked.
         */
        @Test
        fun `a period that has not ended cannot be assembled`(): Unit = runBlocking {
            val august = FilingPeriod(2026, 8)
            coEvery { filings.findByPeriod(august) } returns TaxFilingRecord.open(august, "CZK")

            assertThatThrownBy { runBlocking { service.assemble(august, by = "maker") } }
                .isInstanceOf(TaxConflictException::class.java)
                .hasMessageContaining("has not ended")
        }

        /**
         * §38d withholding is CZK-only (ADR-0033 §E). More than one currency means either the
         * withholding rules changed or the wrong events were consumed — both need a human, and
         * summing across currencies would produce a meaningless figure on a tax return.
         */
        @Test
        fun `a mixed-currency period is refused rather than summed`(): Unit = runBlocking {
            coEvery { filings.findByPeriod(july) } returns TaxFilingRecord.open(july, "CZK")
            coEvery { remittances.totalsFor(july) } returns
                RemittanceTotals(2, 10, BigDecimal("500"), setOf("CZK", "EUR"))

            assertThatThrownBy { runBlocking { service.assemble(july, by = "maker") } }
                .isInstanceOf(TaxConflictException::class.java)
                .hasMessageContaining("one currency")
        }

        @Test
        fun `an unknown period is a not-found`(): Unit = runBlocking {
            coEvery { filings.findByPeriod(july) } returns null

            assertThatThrownBy { runBlocking { service.assemble(july, by = "maker") } }
                .isInstanceOf(TaxFilingNotFoundException::class.java)
        }
    }

    @Nested
    inner class Overdue {

        @Test
        fun `only unfiled periods past their deadline are overdue`(): Unit = runBlocking {
            val may = FilingPeriod(2026, 5) // due 2026-06-30 — long past on the fixed clock
            val filedMay = TaxFilingRecord.open(may, "CZK")
                .assemble(BigDecimal("1"), 1, 1, "maker", Instant.parse("2026-07-01T06:00:00Z"))
                .markFiled("FU-2026-05", "checker", Instant.parse("2026-07-02T06:00:00Z"))
            val june = FilingPeriod(2026, 6) // due 2026-07-31 — past, unfiled
            val openJune = TaxFilingRecord.open(june, "CZK")
            val openJuly = TaxFilingRecord.open(july, "CZK") // due 2026-08-31 — not yet due

            coEvery { filings.findAll() } returns listOf(filedMay, openJune, openJuly)

            assertThat(service.overdue().map { it.period }).containsExactly(june)
        }
    }
}
