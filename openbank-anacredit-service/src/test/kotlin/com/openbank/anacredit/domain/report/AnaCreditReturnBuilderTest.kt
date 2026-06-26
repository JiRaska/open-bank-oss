// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.domain.report

import com.openbank.anacredit.Fixtures
import com.openbank.anacredit.domain.model.CounterpartyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class AnaCreditReturnBuilderTest {

    private val refDate = LocalDate.parse("2026-01-31")

    @Test
    fun `a single legal-entity overdraft above threshold is reported with mapped financials`() {
        val ret = AnaCreditReturnBuilder.build(listOf(Fixtures.exposure()), refDate)

        assertThat(ret.reportableCount).isEqualTo(1)
        val r = ret.records.single()
        assertThat(r.referenceDate).isEqualTo(refDate)
        assertThat(r.outstandingNominalAmount).isEqualByComparingTo("12000.00")
        // 40 000 committed − 12 000 drawn = 28 000 undrawn off-balance-sheet.
        assertThat(r.offBalanceSheetAmount).isEqualByComparingTo("28000.00")
        assertThat(r.defaultStatus).isEqualTo("NOT_IN_DEFAULT")
    }

    @Test
    fun `the 25k threshold aggregates across a debtor's instruments`() {
        // Two 15k commitments for the same debtor -> 30k total -> both clear the 25k threshold,
        // even though neither instrument reaches 25k on its own.
        val a = Fixtures.exposure(instrumentId = "OD-A", committedAmount = BigDecimal("15000"), committedAmountEur = BigDecimal("15000"), drawnAmount = BigDecimal("5000"))
        val b = Fixtures.exposure(instrumentId = "OD-B", committedAmount = BigDecimal("15000"), committedAmountEur = BigDecimal("15000"), drawnAmount = BigDecimal("0"))

        val ret = AnaCreditReturnBuilder.build(listOf(a, b), refDate)

        assertThat(ret.reportableCount).isEqualTo(2)
        assertThat(ret.excludedCount).isEqualTo(0)
    }

    @Test
    fun `a debtor whose aggregate stays below 25k is excluded with the threshold reason`() {
        val a = Fixtures.exposure(instrumentId = "OD-A", debtorId = "LE-SMALL", committedAmount = BigDecimal("10000"), committedAmountEur = BigDecimal("10000"))
        val b = Fixtures.exposure(instrumentId = "OD-B", debtorId = "LE-SMALL", committedAmount = BigDecimal("9000"), committedAmountEur = BigDecimal("9000"))

        val ret = AnaCreditReturnBuilder.build(listOf(a, b), refDate)

        assertThat(ret.reportableCount).isEqualTo(0)
        assertThat(ret.exclusions).allMatch { it.reason == "BELOW_THRESHOLD" }
    }

    @Test
    fun `natural-person debtors are kept out of the dataset but recorded in the audit trail`() {
        val consumer = Fixtures.exposure(debtorType = CounterpartyType.NATURAL_PERSON)
        val ret = AnaCreditReturnBuilder.build(listOf(consumer), refDate)

        assertThat(ret.records).isEmpty()
        assertThat(ret.exclusions.single().reason).isEqualTo("HOUSEHOLD_OUT_OF_SCOPE")
    }

    @Test
    fun `a defaulted instrument is flagged in the dataset`() {
        val ret = AnaCreditReturnBuilder.build(listOf(Fixtures.exposure(defaulted = true)), refDate)
        assertThat(ret.records.single().defaultStatus).isEqualTo("DEFAULT")
    }

    @Test
    fun `mixed book partitions cleanly into reported and excluded`() {
        val big = Fixtures.exposure(instrumentId = "OD-BIG", debtorId = "LE-BIG")
        val consumer = Fixtures.exposure(instrumentId = "OD-CONS", debtorId = "NP-JOE", debtorType = CounterpartyType.NATURAL_PERSON)
        val small = Fixtures.exposure(instrumentId = "OD-SMALL", debtorId = "LE-SMALL", committedAmount = BigDecimal("1000"), committedAmountEur = BigDecimal("1000"))

        val ret = AnaCreditReturnBuilder.build(listOf(big, consumer, small), refDate)

        assertThat(ret.records.map { it.instrumentId }).containsExactly("OD-BIG")
        assertThat(ret.exclusions.map { it.instrumentId }).containsExactlyInAnyOrder("OD-CONS", "OD-SMALL")
    }
}
